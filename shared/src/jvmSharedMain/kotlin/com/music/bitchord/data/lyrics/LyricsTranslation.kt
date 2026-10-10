package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Lightweight, on-demand lyric translation.
 *
 * Translation models are deliberately not installed on the device. A model for
 * every app language would cost tens of megabytes each; translated lyric text
 * is normally only a few kilobytes. Requests are batched and the compact result
 * is kept in a bounded cache under [Context.getCacheDir], so Android may reclaim
 * it and the feature can never grow without limit.
 */
object LyricsTranslation {
    /** Which service wrote a translation, so the player can name it. */
    enum class Engine { GEMINI, GOOGLE }

    sealed interface Result {
        data class Translated(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
            val engine: Engine = Engine.GOOGLE,
        ) : Result

        data class SameLanguage(val language: String) : Result
        data object Unavailable : Result
    }

    sealed interface RomanizationResult {
        data class Romanized(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : RomanizationResult

        data object AlreadyRomanized : RomanizationResult
        data object Unavailable : RomanizationResult
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_ENDPOINT = "https://inputtools.google.com/request"
    // Version 3 invalidates answers cached before the Latin-script retry existed;
    // otherwise a song that once came back untranslated would never take the
    // repaired path after the app is updated.
    private const val CACHE_VERSION = 3
    private const val CACHE_DIRECTORY = "lyrics_translation_v3"
    // Room for roughly two thousand songs: every Gemini answer kept here is a
    // request (and a few seconds) the next play of that song does not spend.
    private const val MAX_CACHE_BYTES = 8L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    // Translations of downloaded songs, kept out of reach of the cache trim and
    // of Android clearing the cache, so the translate button works offline.
    private const val KEPT_DIRECTORY = "lyrics_translation_kept"
    private const val KEPT_NAMESPACE = "kept"
    private const val MAX_KEPT_BYTES = 32L * 1024L * 1024L
    private const val MEANING_DIRECTORY = "song_meaning_v1"
    private const val MAX_MEANING_BYTES = 4L * 1024L * 1024L
    private const val MAX_MEANING_LYRIC_CHARS = 6_000
    private const val ENGINE_GEMINI = "gemini"
    private const val ENGINE_GOOGLE = "google"
    private val markerRegex = Regex("\\uE000[^\\uE001]*\\uE001")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()
    private val memory = object {
        private val entries = object : LinkedHashMap<String, CachedTranslation>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedTranslation>) = size > 12
        }

        @Synchronized fun get(key: String): CachedTranslation? = entries[key]

        @Synchronized fun put(key: String, value: CachedTranslation) {
            entries[key] = value
        }
    }

    /**
     * Where translated lyrics are kept between runs — the phone's cache
     * directory, the desktop's media cache. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    /**
     * Where answers worth keeping for good live — the phone's files directory.
     * Holds the translations saved with downloads and the song explanations.
     * Null falls back to [cacheDir] for explanations and keeps nothing else.
     */
    @Volatile
    var keepDir: File? = null
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Translate with Google's Gemini instead of Google Translate. Gemini reads
     * the whole song at once, so it keeps one voice and speech level across
     * every line and renders lyrics rather than word-for-word text. Set from
     * the app's settings; when off, or when the key is blank, or when Gemini
     * fails for any reason, translation quietly uses Google Translate.
     */
    @Volatile
    var geminiEnabled: Boolean = false

    /** The Gemini API key from Google AI Studio, set by the app's settings. Never committed. */
    @Volatile
    var geminiApiKey: String = ""
        set(value) {
            field = value
            geminiKeyRejected.value = false
        }

    /** True once Gemini refused the current key, so Settings can say why. */
    val geminiKeyRejected = MutableStateFlow(false)

    private const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"

    // Tried in order: when a model is unknown to the key's project or its free
    // quota is used up, the next one usually still answers.
    private val GEMINI_MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val geminiClient by lazy {
        Http.client.newBuilder()
            // A whole song comes back in a few seconds; past this the network is
            // the problem, and Google Translate answering now beats waiting on.
            .callTimeout(40, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** A key Gemini refused (400/401/403). Not retried until the key changes. */
    @Volatile
    private var rejectedGeminiKey: String? = null

    /** After every model reported its quota used up, Gemini rests until then. */
    @Volatile
    private var geminiCooldownUntil = 0L
    private const val GEMINI_COOLDOWN_MS = 5L * 60L * 1000L

    /**
     * The key as Gemini will receive it: only the characters an API key can
     * hold. A pasted key often carries a space or an invisible character, and
     * OkHttp throws rather than send a header containing one.
     */
    private fun cleanGeminiKey(): String =
        geminiApiKey.filter { it in '!'..'~' }

    private val geminiReady: Boolean
        get() {
            if (!geminiEnabled) return false
            val key = cleanGeminiKey()
            return key.isNotEmpty() &&
                key != rejectedGeminiKey &&
                System.currentTimeMillis() >= geminiCooldownUntil
        }

    @Serializable
    private data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
        /** [ENGINE_GEMINI] or [ENGINE_GOOGLE]; entries from before this existed are Google's. */
        val engine: String = ENGINE_GOOGLE,
    )

    private data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        /**
         * Also keep the answer where neither the cache trim nor Android
         * clearing the cache reaches it — for a song that has been downloaded,
         * so its translation is there offline too.
         */
        keep: Boolean = false,
    ): Result {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for. The
        // narrowing is still done, but only where it belongs — in
        // [sameLanguage], which is asking a different question.
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable
        // Lyrics written in Hangul asked into Korean are already Korean: no
        // request, no quota, and the answer is the same one Gemini would give.
        alreadyInTargetScript(slots, target)?.let { return Result.SameLanguage(it) }

        // Kept with a download, keyed by the words alone: the file can be played
        // under either of the two ids its record answers to.
        val keptKey = cacheKey(KEPT_NAMESPACE, target, slots)
        val kept = readEntry(keepDir, KEPT_DIRECTORY, keptKey)?.takeIf { it.texts.size == slots.size }
        val entry = when {
            kept != null && (kept.engine == ENGINE_GEMINI || !geminiReady) -> kept
            // Anything short of a complete, line-for-line answer from either
            // engine falls back to whatever was kept, so the button never
            // stops working offline.
            else -> resolve(trackId, slots, target) ?: kept
        } ?: return Result.Unavailable
        if (keep && entry !== kept) writeEntry(keepDir, KEPT_DIRECTORY, keptKey, entry, MAX_KEPT_BYTES)

        return if (sameLanguage(entry.sourceLanguage, target)) {
            Result.SameLanguage(entry.sourceLanguage)
        } else {
            Result.Translated(
                lines = rebuild(lines, slots, entry.texts),
                sourceLanguage = entry.sourceLanguage,
                fromCache = entry === kept,
                engine = if (entry.engine == ENGINE_GEMINI) Engine.GEMINI else Engine.GOOGLE,
            )
        }
    }

    /** Gemini where it is set up and answers in full, Google Translate otherwise. */
    private suspend fun resolve(
        trackId: String,
        slots: List<TextSlot>,
        target: String,
    ): CachedTranslation? {
        if (geminiReady) {
            val viaGemini = try {
                geminiEntry(trackId, slots, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Whatever went wrong, the lyric sheet must not: fall through.
                null
            }
            viaGemini?.let { return it }
        }
        return try {
            googleEntry(trackId, slots, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun googleEntry(
        trackId: String,
        slots: List<TextSlot>,
        target: String,
    ): CachedTranslation? {
        val cacheKey = cacheKey(trackId, target, slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return cached
        }

        val batches = batches(slots)
        val answers = coroutineScope {
            // Two short requests at a time keeps a long lyric fast without
            // competing with playback for every connection in the pool.
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return null
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        if (source.isBlank()) return null

        var translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return null

        // Google's ordinary auto-detection understands many Latin-script
        // Hindi/Urdu/Punjabi lyrics, but its NMT occasionally returns whole
        // phrases unchanged ("tera hone laga hoon" is a common example). If a
        // sizeable part of a Latin-script source survived untouched, use
        // Google's Input Tools to restore the detected language's native script
        // and translate that. Keep the first answer unless the retry actually
        // transforms more of the song, so names and genuinely bilingual lyrics
        // do not get worse merely because they contain Latin text.
        if (
            !sameLanguage(source, target) &&
            predominantlyLatin(slots) &&
            unchangedWeight(slots, translated) * 3 >= slots.sumOf { it.text.length }
        ) {
            val retried = retryRomanizedTranslation(batches, source, target)
            if (
                retried != null &&
                retried.size == slots.size &&
                unchangedWeight(slots, retried) < unchangedWeight(slots, translated)
            ) {
                translated = retried
            }
        }
        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
            engine = ENGINE_GOOGLE,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)
        return entry
    }

    suspend fun romanize(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable
        if (!hasNonLatinLetters(slots)) return RomanizationResult.AlreadyRomanized

        // Romanization always ends in Latin script. The translation destination
        // is still sent as `tl` because the web endpoint requires it, but it is
        // deliberately excluded from this cache key: changing "translate to"
        // cannot change how the source language is pronounced.
        val cacheKey = cacheKey(trackId, "romanize", slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                lines = rebuild(lines, slots, cached.texts),
                sourceLanguage = cached.sourceLanguage,
                fromCache = true,
            )
        }

        val target = targetLanguageTag.trim().ifBlank { "en" }
        val batches = batches(slots)
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestRomanizationBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return RomanizationResult.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        val romanized = complete.flatMap { it.translations }
        if (source.isBlank() || romanized.size != slots.size) return RomanizationResult.Unavailable
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {
            // Non-Latin input reached this point, so an unchanged response is
            // an unsupported/failed romanization rather than an already-Latin
            // lyric. The fast all-Latin check above handles the real no-op case.
            return RomanizationResult.Unavailable
        }

        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = "Latn",
            texts = romanized,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)
        return RomanizationResult.Romanized(
            lines = rebuild(lines, slots, romanized),
            sourceLanguage = source,
            fromCache = false,
        )
    }

    /**
     * Gemini's answer for the whole song, cached separately from Google's so
     * switching engines never shows the other engine's text. Null whenever the
     * answer can't be used as-is, which sends the caller back to Google.
     */
    private suspend fun geminiEntry(
        trackId: String,
        slots: List<TextSlot>,
        target: String,
    ): CachedTranslation? {
        val cacheKey = cacheKey("gemini\u0000$trackId", target, slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also { memory.put(cacheKey, it) }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            // Entries written before engines were recorded carry the default.
            return cached.copy(engine = ENGINE_GEMINI)
        }
        val fresh = requestGemini(slots, target) ?: return null
        if (fresh.translations.size != slots.size) return null
        return CachedTranslation(
            sourceLanguage = fresh.sourceLanguage,
            targetLanguage = target,
            // A line left empty (often a bare "oh" or "yeah") keeps the
            // original rather than showing a blank translation under it.
            texts = fresh.translations.mapIndexed { index, text -> text.ifBlank { slots[index].text } },
            engine = ENGINE_GEMINI,
        ).also {
            memory.put(cacheKey, it)
            writeCache(cacheKey, it)
        }
    }

    private fun geminiInstruction(target: String, count: Int): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val korean = canonicalLanguage(target) == "ko"
        return buildString {
            appendLine("You are a professional lyric translator localising song lyrics for a music app.")
            appendLine("Translate every input line into $languageName ($target).")
            appendLine()
            appendLine("Rules:")
            appendLine("1. Return exactly $count translations in \"translations\", one per input line, in the same order. Never merge, split, skip, reorder or add lines.")
            appendLine("2. Read the whole song first. Keep one consistent narrator voice, speech level and tone across all lines.")
            if (korean) {
                appendLine("   For Korean, pick either casual speech (반말) or polite speech (존댓말) to suit the song and use it for every line. Never mix them. Most songs read naturally in 반말.")
                appendLine("   Write natural modern Korean as on an official Korean lyric sheet, not translationese. Avoid stiff words such as '당신' unless the song truly calls for it.")
            }
            appendLine("3. Translate meaning and feeling, not word for word. Keep metaphors, idioms and wordplay meaningful in the target language. Keep each line roughly as short as the original.")
            appendLine("4. Keep names of people, places, brands and titles as they are. Lines that are only vocal sounds (oh, yeah, la la, na na) may be kept as they are.")
            appendLine("5. Items with role \"section\" are labels such as Verse, Chorus or Bridge. Translate them as short labels.")
            appendLine("6. Items with role \"backing\" are background vocals. Translate them like the other lines.")
            appendLine("7. Output only the translated text. No quotes around lines, no notes, no romanisation, no explanations.")
            appendLine("8. Set \"source_language\" to the ISO 639-1 code of the song's main original language.")
        }
    }

    private suspend fun requestGemini(slots: List<TextSlot>, target: String): BatchAnswer? {
        val key = cleanGeminiKey()
        if (key.isEmpty()) return null
        val input = buildJsonObject {
            put("target_language", target)
            put("line_count", slots.size)
            putJsonArray("lines") {
                slots.forEachIndexed { index, slot ->
                    add(buildJsonObject {
                        put("i", index)
                        put(
                            "role",
                            when {
                                slot.sectionHeader -> "section"
                                slot.background -> "backing"
                                else -> "lyric"
                            },
                        )
                        put("text", slot.text)
                    })
                }
            }
        }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", geminiInstruction(target, slots.size)) })
                }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", input.toString()) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("source_language") { put("type", "STRING") }
                        putJsonObject("translations") {
                            put("type", "ARRAY")
                            putJsonObject("items") { put("type", "STRING") }
                        }
                    }
                    putJsonArray("required") {
                        add("source_language")
                        add("translations")
                    }
                }
            }
        }.toString()

        return askGemini(body) { parseGemini(it, slots.size) }
    }

    /**
     * Posts [body] to each Gemini model in turn until one answers with
     * something [parse] accepts. Null when none does, for whatever reason.
     */
    private suspend fun <T : Any> askGemini(body: String, parse: (String) -> T?): T? {
        val key = cleanGeminiKey()
        if (key.isEmpty() || key == rejectedGeminiKey) return null
        if (System.currentTimeMillis() < geminiCooldownUntil) return null
        var quotaRefusals = 0
        for (model in GEMINI_MODELS) {
            val request = Request.Builder()
                .url(GEMINI_ENDPOINT.format(model))
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val (code, text) = try {
                geminiClient.newCall(request).awaitResponse()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return null
            }
            when {
                code in 200..299 -> {
                    val parsed = parse(text)
                    if (parsed != null) return parsed
                    // A malformed answer from one model is worth one more try
                    // on the other before giving up on Gemini for this song.
                }
                // Exhausted quota: the next model has its own and may answer.
                code == 429 -> quotaRefusals++
                // Unknown model or a transient server error: try the next one.
                code == 404 || code >= 500 -> Unit
                // A bad or restricted key fails the same everywhere, so stop
                // asking with it rather than delay every translation.
                code == 401 || code == 403 ||
                    (code == 400 && text.contains("API_KEY", ignoreCase = true)) -> {
                    rejectedGeminiKey = key
                    geminiKeyRejected.value = true
                    return null
                }
                // Any other 400 is about the request; another model may accept it.
                code == 400 -> Unit
                else -> return null
            }
        }
        if (quotaRefusals == GEMINI_MODELS.size) {
            geminiCooldownUntil = System.currentTimeMillis() + GEMINI_COOLDOWN_MS
        }
        return null
    }

    /** The model's own text out of a generateContent response, thoughts and fences removed. */
    private fun geminiText(response: String): String {
        val root = json.parseToJsonElement(response).jsonObject
        val parts = root["candidates"]!!.jsonArray[0].jsonObject["content"]!!
            .jsonObject["parts"]!!.jsonArray
        return parts
            .map { it.jsonObject }
            .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun parseGemini(response: String, expected: Int): BatchAnswer? = runCatching {
        val text = geminiText(response)
        val answer = json.parseToJsonElement(text).jsonObject
        val translations = answer["translations"]!!.jsonArray.map {
            it.jsonPrimitive.contentOrNull.orEmpty().trim()
        }
        if (translations.size != expected || translations.all { it.isBlank() }) return@runCatching null
        val source = answer["source_language"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        BatchAnswer(
            translations = translations,
            sourceLanguage = source.ifBlank { "und" },
            sourceWeight = text.length,
        )
    }.getOrNull()

    /** Status code and body, for callers that branch on the code. */
    private suspend fun Call.awaitResponse(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (continuation.isActive) continuation.resume(it.code to body)
                }
            }
        })
    }

    private fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isNotBlank()) {
                val header = Genius.isSectionHeader(line.text)
                add(
                    TextSlot(
                        lineIndex = index,
                        background = false,
                        text = if (header) line.text.removePrefix("[").removeSuffix("]").trim() else line.text,
                        sectionHeader = header,
                    ),
                )
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { background ->
                add(TextSlot(index, background = true, background.text, sectionHeader = false))
            }
        }
    }

    private fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"

    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", sourceLanguage)
            .add("tl", target)
            .add("dt", "t")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = translatedBody.split(markerRegex).map(String::trim)
            if (parts.size != batch.slots.size || parts.any { it.isBlank() }) return@runCatching null
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun requestRomanizationBatch(batch: Batch, target: String): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", "auto")
            .add("tl", target)
            .add("dt", "rm")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = romanizedBody.split(markerRegex).map(String::trim)
            if (source.isBlank() || parts.size != batch.slots.size || parts.any { it.isBlank() }) {
                return@runCatching null
            }
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun retryRomanizedTranslation(
        batches: List<Batch>,
        sourceLanguage: String,
        target: String,
    ): List<String>? = coroutineScope {
        val source = canonicalLanguage(sourceLanguage)
        if (source.isBlank() || source == "en") return@coroutineScope null
        val answers = batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
            group.map { batch ->
                async {
                    val nativePayload = requestNativeScript(batch.payload, source) ?: return@async null
                    requestBatch(batch.copy(payload = nativePayload), target, source)
                }
            }.awaitAll()
        }
        if (answers.any { it == null }) null else answers.filterNotNull().flatMap { it.translations }
    }

    private suspend fun requestNativeScript(text: String, sourceLanguage: String): String? {
        val body = FormBody.Builder()
            .add("text", text)
            .add("itc", "$sourceLanguage-t-i0-und")
            .add("num", "1")
            .add("cp", "0")
            .add("cs", "1")
            .add("ie", "utf-8")
            .add("oe", "utf-8")
            .build()
        val request = Request.Builder()
            .url(INPUT_TOOLS_ENDPOINT)
            .header("User-Agent", "BitChord/1.7.1")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return@runCatching null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun predominantlyLatin(slots: List<TextSlot>): Boolean {
        var latin = 0
        var other = 0
        slots.forEach { slot ->
            slot.text.codePoints().forEach { codePoint ->
                if (Character.isLetter(codePoint)) {
                    if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) latin++
                    else other++
                }
            }
        }
        return latin > 0 && latin >= other * 4
    }

    private fun hasNonLatinLetters(slots: List<TextSlot>): Boolean = slots.any { slot ->
        slot.text.codePoints().anyMatch { codePoint ->
            Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
        }
    }

    private fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    if (!continuation.isActive) return
                    if (body != null) continuation.resume(body)
                    else continuation.resumeWithException(IOException("Translation HTTP ${it.code}"))
                }
            }
        })
    }

    private fun rebuild(
        original: List<LyricLine>,
        slots: List<TextSlot>,
        translated: List<String>,
    ): List<LyricLine> {
        val byLine = slots.zip(translated).groupBy { it.first.lineIndex }
        return original.mapIndexed { index, line ->
            val entries = byLine[index].orEmpty()
            val lead = entries.firstOrNull { !it.first.background }
            val backing = entries.firstOrNull { it.first.background }
            val leadText = lead?.let { (slot, text) -> if (slot.sectionHeader) "[$text]" else text }
                ?: line.text
            line.copy(
                text = leadText,
                words = retimeWords(line, leadText),
                timingSource = line.takeIf { it.isWordSynced },
                background = line.background?.let { source ->
                    val text = backing?.second ?: source.text
                    source.copy(
                        text = text,
                        words = retimeWords(source, text),
                        timingSource = source.takeIf { it.isWordSynced },
                    )
                },
            )
        }
    }

    /**
     * Keep the source vocal bounds without inventing translated word timings.
     * LyricLine.timingSource projects the original non-uniform character sweep
     * onto the new text, preserving holds, pauses and the original glow envelope.
     */
    private fun retimeWords(source: LyricLine, translated: String): List<LyricWord> {
        if (source.words.isEmpty()) return emptyList()
        if (translated.isEmpty()) return emptyList()
        val start = source.words.first().startMs
        return listOf(LyricWord(start, source.words.last().endMs, translated))
    }

    private fun canonicalLanguage(tag: String): String = when (
        Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    ) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    }

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    private fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String {
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach { append('\u0000').append(it.text) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun readCache(key: String): CachedTranslation? =
        readEntry(cacheDir, CACHE_DIRECTORY, key)

    private suspend fun writeCache(key: String, value: CachedTranslation) =
        writeEntry(cacheDir, CACHE_DIRECTORY, key, value, MAX_CACHE_BYTES)

    private suspend fun readEntry(root: File?, directory: String, key: String): CachedTranslation? =
        readStored(root, directory, key)?.let { text ->
            runCatching { json.decodeFromString<CachedTranslation>(text) }.getOrNull()
        }?.takeIf { it.version == CACHE_VERSION }

    private suspend fun writeEntry(
        root: File?,
        directory: String,
        key: String,
        value: CachedTranslation,
        maxBytes: Long,
    ) = writeStored(root, directory, key, json.encodeToString(value), maxBytes)

    private suspend fun readStored(root: File?, directoryName: String, key: String): String? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val base = root ?: return@withLock null
                val file = File(File(base, directoryName), "$key.json.gz")
                if (!file.isFile) return@withLock null
                runCatching {
                    val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use { it.readText() }
                    file.setLastModified(System.currentTimeMillis())
                    value
                }.getOrNull()
            }
        }

    private suspend fun writeStored(
        root: File?,
        directoryName: String,
        key: String,
        text: String,
        maxBytes: Long,
    ) = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val base = root ?: return@withLock
            val directory = File(base, directoryName)
            if (!directory.exists() && !directory.mkdirs()) return@withLock
            val destination = File(directory, "$key.json.gz")
            val temporary = File(directory, "$key.tmp")
            runCatching {
                GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                    it.write(text)
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                trimCache(directory, maxBytes)
            }.onFailure { temporary.delete() }
        }
    }

    private fun trimCache(directory: File, maxBytes: Long) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > maxBytes) file.delete()
        }
    }

    /**
     * The language [slots] are already written in, when the script alone says
     * so and it is the one asked for. Only Korean: Hangul is Korean's alone,
     * which no other target's script can claim.
     */
    private fun alreadyInTargetScript(slots: List<TextSlot>, target: String): String? {
        if (canonicalLanguage(target) != "ko") return null
        var hangul = 0
        var other = 0
        slots.forEach { slot ->
            slot.text.codePoints().forEach { codePoint ->
                if (Character.isLetter(codePoint)) {
                    if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL) hangul++
                    else other++
                }
            }
        }
        return "ko".takeIf { hangul > 0 && hangul >= other * 4 }
    }

    // ---- What a song means ---------------------------------------------------

    @Serializable
    data class SongMeaning(
        /** One line on what the song is about. */
        val summary: String,
        /** A few short paragraphs, separated by blank lines. */
        val meaning: String,
        val highlights: List<MeaningHighlight> = emptyList(),
    )

    /** One line of the song quoted, and what it says. */
    @Serializable
    data class MeaningHighlight(val line: String, val note: String)

    sealed interface MeaningResult {
        data class Ready(val meaning: SongMeaning) : MeaningResult
        /** No Gemini key is set, or Gemini refused the one that is. */
        data object NoKey : MeaningResult
        data object Unavailable : MeaningResult
    }

    /** True while a Gemini key is set that Gemini has not refused. */
    val geminiKeyUsable: Boolean
        get() {
            val key = cleanGeminiKey()
            return key.isNotEmpty() && key != rejectedGeminiKey
        }

    private val meanings = object {
        private val entries = object : LinkedHashMap<String, SongMeaning>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, SongMeaning>) = size > 8
        }

        @Synchronized fun get(key: String): SongMeaning? = entries[key]

        @Synchronized fun put(key: String, value: SongMeaning) {
            entries[key] = value
        }
    }

    /**
     * Gemini's explanation of the song — what it is about, its images and
     * slang, a few lines worth a note — written in [targetLanguageTag].
     * Asked once per song and language, then kept for good.
     */
    suspend fun explain(
        trackId: String,
        title: String,
        artist: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): MeaningResult {
        val target = targetLanguageTag.trim().ifBlank { "en" }
        val key = cacheKey("meaning\u0000$trackId", target, emptyList())
        val root = keepDir ?: cacheDir
        meanings.get(key)?.let { return MeaningResult.Ready(it) }
        readStored(root, MEANING_DIRECTORY, key)
            ?.let { text -> runCatching { json.decodeFromString<SongMeaning>(text) }.getOrNull() }
            ?.let {
                meanings.put(key, it)
                return MeaningResult.Ready(it)
            }
        if (!geminiKeyUsable) return MeaningResult.NoKey

        val lyricText = lines
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(MAX_MEANING_LYRIC_CHARS)
        if (lyricText.isBlank()) return MeaningResult.Unavailable

        val input = buildJsonObject {
            put("title", title)
            put("artist", artist)
            put("lyrics", lyricText)
        }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", meaningInstruction(target)) })
                }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", input.toString()) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("summary") { put("type", "STRING") }
                        putJsonObject("meaning") { put("type", "STRING") }
                        putJsonObject("highlights") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("line") { put("type", "STRING") }
                                    putJsonObject("note") { put("type", "STRING") }
                                }
                                putJsonArray("required") {
                                    add("line")
                                    add("note")
                                }
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("summary")
                        add("meaning")
                        add("highlights")
                    }
                }
            }
        }.toString()

        val answer = try {
            askGemini(body) { parseMeaning(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (answer == null) {
            return if (geminiKeyUsable) MeaningResult.Unavailable else MeaningResult.NoKey
        }
        meanings.put(key, answer)
        writeStored(root, MEANING_DIRECTORY, key, json.encodeToString(answer), MAX_MEANING_BYTES)
        return MeaningResult.Ready(answer)
    }

    private fun meaningInstruction(target: String): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val korean = canonicalLanguage(target) == "ko"
        return buildString {
            appendLine("You write short, insightful song explanations for a music app, like the notes on a lyric site.")
            appendLine("Write everything in $languageName ($target).")
            if (korean) {
                appendLine("Use natural, friendly modern Korean in 해요체. Never use '당신'. Avoid translationese.")
            }
            appendLine()
            appendLine("Return JSON with:")
            appendLine("- \"summary\": one sentence saying what the song is about${if (korean) " (under 60 Korean characters)" else ""}.")
            appendLine("- \"meaning\": two or three short paragraphs separated by one blank line. First the story and the feeling of the song, then its key images, metaphors and slang and what they mean.")
            appendLine("- \"highlights\": up to four items. Each has \"line\", copied exactly from the lyrics given, and \"note\", one or two sentences on what that line means.")
            appendLine()
            appendLine("Base everything on the lyrics given. Mention background about the artist or the song only if you are certain it is true. Never invent facts, chart records, interviews or quotes.")
            appendLine("Plain text only: no markdown, no bullet characters, no emojis.")
        }
    }

    private fun parseMeaning(response: String): SongMeaning? = runCatching {
        val parsed = json.decodeFromString<SongMeaning>(geminiText(response))
        val cleaned = parsed.copy(
            summary = parsed.summary.trim(),
            meaning = parsed.meaning.trim(),
            highlights = parsed.highlights
                .map { MeaningHighlight(it.line.trim(), it.note.trim()) }
                .filter { it.line.isNotEmpty() && it.note.isNotEmpty() }
                .take(4),
        )
        cleaned.takeIf { it.summary.isNotEmpty() && it.meaning.isNotEmpty() }
    }.getOrNull()
}
