package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import com.music.bitchord.ko.KoLyricsTranslation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
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
    internal const val CACHE_VERSION = 3 // [KO] fork access
    private const val CACHE_DIRECTORY = "lyrics_translation_v3"
    // Room for roughly two thousand songs: every Gemini answer kept here is a
    // request (and a few seconds) the next play of that song does not spend.
    private const val MAX_CACHE_BYTES = 30L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    // The automatic and manual translation paths leave the button loading until
    // one of them answers. Five Gemini models at 40 s each and then Google's
    // 12 s is over three minutes on a slow proxy; this budget gives one Gemini
    // attempt and the Google fallback room while still ending the wait. A kept
    // answer is still used when the deadline passes (see [translate]).
    internal const val TRANSLATION_DEADLINE_MS = 60_000L
    // The Google client's own call timeout is 12 s; this reserve covers it plus
    // request overhead so a slow model loop cannot consume the fallback's slot.
    internal const val GOOGLE_FALLBACK_RESERVE_MS = 15_000L

    internal data class TranslationDeadlines(
        val totalMs: Long = TRANSLATION_DEADLINE_MS,
        val googleReserveMs: Long = GOOGLE_FALLBACK_RESERVE_MS,
    ) {
        val geminiMs: Long get() = (totalMs - googleReserveMs).coerceAtLeast(1L)
    }
    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    internal const val ENGINE_GEMINI = "gemini" // [KO] gemini
    internal const val ENGINE_GOOGLE = "google" // [KO] gemini
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

    internal fun rememberedTranslation(key: String): CachedTranslation? = memory.get(key) // [KO] fork access

    internal fun rememberTranslation(key: String, value: CachedTranslation) { // [KO] fork access
        memory.put(key, value)
    }

    /**
     * Where translated lyrics are kept between runs — the phone's cache
     * directory, the desktop's media cache. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    /** Where answers worth keeping for good live. See [KoLyricsTranslation.keepDir]. */
    var keepDir: File?
        get() = KoLyricsTranslation.keepDir // [KO] kept-cache
        set(value) {
            KoLyricsTranslation.keepDir = value // [KO] kept-cache
        }
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** Gemini settings and state live in [KoLyricsTranslation]. */
    var geminiEnabled: Boolean
        get() = KoLyricsTranslation.geminiEnabled // [KO] gemini
        set(value) {
            KoLyricsTranslation.geminiEnabled = value // [KO] gemini
        }

    var geminiApiKey: String
        get() = KoLyricsTranslation.geminiApiKey // [KO] gemini
        set(value) {
            KoLyricsTranslation.geminiApiKey = value // [KO] gemini
        }

    /** True once Gemini refused the current key, so Settings can say why. */
    val geminiKeyRejected get() = KoLyricsTranslation.geminiKeyRejected // [KO] gemini

    /** True while a Gemini key is set that Gemini has not refused. */
    val geminiKeyUsable get() = KoLyricsTranslation.geminiKeyUsable // [KO] gemini

    @Serializable
    internal data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
        /** [ENGINE_GEMINI] or [ENGINE_GOOGLE]; entries from before this existed are Google's. */
        val engine: String = ENGINE_GOOGLE,
    )

    internal data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    internal data class BatchAnswer( // [KO] fork access
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
    ): Result = KoLyricsTranslation.translateWith( // [KO] translation-pipeline
        trackId = trackId,
        lines = lines,
        targetLanguageTag = targetLanguageTag,
        keep = keep,
        deadlines = TranslationDeadlines(),
        gemini = { track, slots, target -> KoLyricsTranslation.geminiEntry(track, slots, target) },
        google = { track, slots, target -> googleEntry(track, slots, target) },
    )

    internal suspend fun translateWith(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        keep: Boolean,
        deadlines: TranslationDeadlines,
        gemini: suspend (String, List<TextSlot>, String) -> CachedTranslation?,
        google: suspend (String, List<TextSlot>, String) -> CachedTranslation?,
    ): Result = KoLyricsTranslation.translateWith( // [KO] translation-pipeline
        trackId = trackId,
        lines = lines,
        targetLanguageTag = targetLanguageTag,
        keep = keep,
        deadlines = deadlines,
        gemini = gemini,
        google = google,
    )

    internal suspend fun <T> withinTranslationDeadline(
        timeoutMs: Long = TRANSLATION_DEADLINE_MS,
        block: suspend () -> T,
    ): T? = KoLyricsTranslation.withinTranslationDeadline(timeoutMs, block) // [KO] translation-deadline

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
        val answers = withinTranslationDeadline {
            coroutineScope {
                batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                    group.map { batch -> async { requestRomanizationBatch(batch, target) } }.awaitAll()
                }
            }
        } ?: return RomanizationResult.Unavailable
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

    internal fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList { // [KO] fork access
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

    internal fun rebuild( // [KO] fork access
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

    internal fun canonicalLanguage(tag: String): String = when ( // [KO] fork access
        Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    ) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    }

    /** Chinese script pairs still differ; the rest compare by base language. */
    internal fun sameLanguage(first: String, second: String): Boolean =
        KoLyricsTranslation.sameLanguage(first, second) // [KO] chinese-script

    internal fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String { // [KO] fork access
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach { append('\u0000').append(it.text) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // Under the app's own files when it has them, where Android's cache
    // clearing doesn't reach: a song's translation is made once. One made
    // before then is still read from the cache it was written to.
    internal suspend fun readCache(key: String): CachedTranslation? = // [KO] fork access
        readEntry(keepDir ?: cacheDir, CACHE_DIRECTORY, key)
            ?: cacheDir?.takeIf { keepDir != null }?.let { readEntry(it, CACHE_DIRECTORY, key) }

    internal suspend fun writeCache(key: String, value: CachedTranslation) = // [KO] fork access
        writeEntry(keepDir ?: cacheDir, CACHE_DIRECTORY, key, value, MAX_CACHE_BYTES)

    internal suspend fun readEntry(root: File?, directory: String, key: String): CachedTranslation? = // [KO] fork access
        readStored(root, directory, key)?.let { text ->
            runCatching { json.decodeFromString<CachedTranslation>(text) }.getOrNull()
        }?.takeIf { it.version == CACHE_VERSION }

    internal suspend fun writeEntry( // [KO] fork access
        root: File?,
        directory: String,
        key: String,
        value: CachedTranslation,
        maxBytes: Long,
    ) = writeStored(root, directory, key, json.encodeToString(value), maxBytes)

    internal suspend fun readStored(root: File?, directoryName: String, key: String): String? = // [KO] fork access
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

    internal suspend fun writeStored( // [KO] fork access
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

    // ---- [KO] Korean fork features, implemented in com.music.bitchord.ko ----

    @Serializable
    data class SongMeaning( // [KO] song-meaning
        /** One line on what the song is about. */
        val summary: String,
        /** A few short paragraphs, separated by blank lines. */
        val meaning: String,
        val highlights: List<MeaningHighlight> = emptyList(),
    )

    /** One line of the song quoted, and what it says. */
    @Serializable
    data class MeaningHighlight(val line: String, val note: String) // [KO] song-meaning

    sealed interface MeaningResult { // [KO] song-meaning
        data class Ready(val meaning: SongMeaning) : MeaningResult
        /** No Gemini key is set, or Gemini refused the one that is. */
        data object NoKey : MeaningResult
        data object Unavailable : MeaningResult
    }

    /** What Gemini made of a Hangul title: the original's name, or that there is none. */
    sealed interface NativeNameResult { // [KO] native-name
        data class Found(val title: String, val artist: String) : NativeNameResult
        /** Gemini answered: the song is Korean, or it does not know it. Worth remembering. */
        data object NotForeign : NativeNameResult
        /** No key, no quota, no network: nothing learned, worth asking again later. */
        data object Unavailable : NativeNameResult
    }

    /** A lyric line to place: its text, and when the original recording sings it. */
    data class LineToPlace(val text: String, val referenceMs: Long) // [KO] video-timing

    suspend fun explain(
        trackId: String,
        title: String,
        artist: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): MeaningResult = KoLyricsTranslation.explain( // [KO] song-meaning
        trackId, title, artist, lines, targetLanguageTag,
    )

    suspend fun nativeName(title: String, artist: String): NativeNameResult =
        KoLyricsTranslation.nativeName(title, artist) // [KO] native-name

    suspend fun placeLinesInVideo(
        videoId: String,
        lines: List<LineToPlace>,
        audioWav: ByteArray? = null,
    ): List<Long?>? = KoLyricsTranslation.placeLinesInVideo(videoId, lines, audioWav) // [KO] video-timing

    suspend fun transcribeVideo(
        videoId: String,
        durationMs: Long,
        audioWav: ByteArray? = null,
    ): List<LyricLine>? = KoLyricsTranslation.transcribeVideo(videoId, durationMs, audioWav) // [KO] ai-lyrics

    internal fun parseTranscript(text: String, durationMs: Long): List<LyricLine>? =
        KoLyricsTranslation.parseTranscript(text, durationMs) // [KO] ai-lyrics

    internal fun parsePlacements(text: String, count: Int): List<Long?>? =
        KoLyricsTranslation.parsePlacements(text, count) // [KO] ai-lyrics

    internal fun parseClock(raw: String): Long? =
        KoLyricsTranslation.parseClock(raw) // [KO] ai-lyrics

}
