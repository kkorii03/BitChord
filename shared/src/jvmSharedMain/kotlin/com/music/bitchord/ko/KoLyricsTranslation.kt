package com.music.bitchord.ko

import com.music.bitchord.data.Http
import com.music.bitchord.data.LocaleTags
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.lyrics.LyricsTranslation.CachedTranslation
import com.music.bitchord.data.lyrics.LyricsTranslation.TextSlot
import com.music.bitchord.data.lyrics.LyricsTranslation.TranslationDeadlines
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Korean fork additions to [LyricsTranslation], kept here so the upstream file
 * only carries hook lines. See docs/ko-fork-layout.md.
 */
object KoLyricsTranslation {
    // Translations of downloaded songs, kept out of reach of the cache trim and
    // of Android clearing the cache, so the translate button works offline.
    private const val KEPT_DIRECTORY = "lyrics_translation_kept"
    private const val KEPT_NAMESPACE = "kept"
    private const val MAX_KEPT_BYTES = 32L * 1024L * 1024L
    private const val MEANING_DIRECTORY = "song_meaning_v1"
    private const val MAX_MEANING_BYTES = 4L * 1024L * 1024L
    private const val MAX_MEANING_LYRIC_CHARS = 6_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Where answers worth keeping for good live — the phone's files directory.
     * Holds the translations saved with downloads and the song explanations.
     * Null falls back to [LyricsTranslation.cacheDir] for explanations and keeps nothing else.
     */
    @Volatile
    var keepDir: File? = null

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
    // Each model has its own per-minute quota on a free key, and a song start
    // asks several things at once (this song, the next, original names) — so
    // more of them to fall through before Google Translate. Unknown ones 404
    // and are skipped at no cost.
    private val GEMINI_MODELS = listOf(
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash-lite", "gemini-3.5-flash", "gemini-3.6-flash",
    )
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

    /** True while a Gemini key is set that Gemini has not refused. */
    val geminiKeyUsable: Boolean
        get() {
            val key = cleanGeminiKey()
            return key.isNotEmpty() && key != rejectedGeminiKey
        }

    internal suspend fun translateWith(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        keep: Boolean,
        deadlines: TranslationDeadlines,
        gemini: suspend (String, List<TextSlot>, String) -> CachedTranslation?,
        google: suspend (String, List<TextSlot>, String) -> CachedTranslation?,
    ): LyricsTranslation.Result {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for. The
        // narrowing is still done, but only where it belongs — in
        // [sameLanguage], which is asking a different question.
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return LyricsTranslation.Result.Unavailable

        val slots = LyricsTranslation.flatten(lines)
        if (slots.isEmpty()) return LyricsTranslation.Result.Unavailable
        // Lyrics written in Hangul asked into Korean are already Korean: no
        // request, no quota, and the answer is the same one Gemini would give.
        alreadyInTargetScript(slots, target)?.let { return LyricsTranslation.Result.SameLanguage(it) }

        // Kept with a download, keyed by the words alone: the file can be played
        // under either of the two ids its record answers to.
        val keptKey = LyricsTranslation.cacheKey(KEPT_NAMESPACE, target, slots)
        val kept = LyricsTranslation.readEntry(keepDir, KEPT_DIRECTORY, keptKey)?.takeIf { it.texts.size == slots.size }
        val entry = when {
            kept != null && (kept.engine == LyricsTranslation.ENGINE_GEMINI || !geminiReady) -> kept
            // Anything short of a complete, line-for-line answer from either
            // engine falls back to whatever was kept, so the button never
            // stops working offline.
            else -> withinTranslationDeadline(deadlines.totalMs) {
                resolve(trackId, slots, target, deadlines, gemini, google)
            } ?: kept
        } ?: return LyricsTranslation.Result.Unavailable
        if (keep && entry !== kept) LyricsTranslation.writeEntry(keepDir, KEPT_DIRECTORY, keptKey, entry, MAX_KEPT_BYTES)

        return if (LyricsTranslation.sameLanguage(entry.sourceLanguage, target)) {
            LyricsTranslation.Result.SameLanguage(entry.sourceLanguage)
        } else {
            LyricsTranslation.Result.Translated(
                lines = LyricsTranslation.rebuild(lines, slots, entry.texts),
                sourceLanguage = entry.sourceLanguage,
                fromCache = entry === kept,
                engine = if (entry.engine == LyricsTranslation.ENGINE_GEMINI) {
                    LyricsTranslation.Engine.GEMINI
                } else {
                    LyricsTranslation.Engine.GOOGLE
                },
            )
        }
    }

    /**
     * Runs [block] under the translation request budget. Null means the budget
     * ran out, which callers treat like any other unavailable answer; a kept
     * translation is tried before this and still wins afterwards.
     */
    internal suspend fun <T> withinTranslationDeadline(
        timeoutMs: Long = LyricsTranslation.TRANSLATION_DEADLINE_MS,
        block: suspend () -> T,
    ): T? = withTimeoutOrNull(timeoutMs) { block() }

    /** Gemini where it is set up and answers in full, Google Translate otherwise. */
    private suspend fun resolve(
        trackId: String,
        slots: List<TextSlot>,
        target: String,
        deadlines: TranslationDeadlines,
        gemini: suspend (String, List<TextSlot>, String) -> CachedTranslation?,
        google: suspend (String, List<TextSlot>, String) -> CachedTranslation?,
    ): CachedTranslation? {
        if (geminiReady) {
            val viaGemini = try {
                withinTranslationDeadline(deadlines.geminiMs) {
                    gemini(trackId, slots, target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Whatever went wrong, the lyric sheet must not: fall through.
                null
            }
            viaGemini?.let { return it }
        }
        return try {
            google(trackId, slots, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gemini's answer for the whole song, cached separately from Google's so
     * switching engines never shows the other engine's text. Null whenever the
     * answer can't be used as-is, which sends the caller back to Google.
     */
    internal suspend fun geminiEntry(
        trackId: String,
        slots: List<TextSlot>,
        target: String,
    ): CachedTranslation? {
        val cacheKey = LyricsTranslation.cacheKey("gemini\u0000$trackId", target, slots)
        val cached = LyricsTranslation.rememberedTranslation(cacheKey)
            ?: LyricsTranslation.readCache(cacheKey)?.also { LyricsTranslation.rememberTranslation(cacheKey, it) }
        if (cached != null && cached.version == LyricsTranslation.CACHE_VERSION && cached.texts.size == slots.size) {
            // Entries written before engines were recorded carry the default.
            return cached.copy(engine = LyricsTranslation.ENGINE_GEMINI)
        }
        val fresh = requestGemini(slots, target) ?: return null
        if (fresh.translations.size != slots.size) return null
        return CachedTranslation(
            sourceLanguage = fresh.sourceLanguage,
            targetLanguage = target,
            // A line left empty (often a bare "oh" or "yeah") keeps the
            // original rather than showing a blank translation under it.
            texts = fresh.translations.mapIndexed { index, text -> text.ifBlank { slots[index].text } },
            engine = LyricsTranslation.ENGINE_GEMINI,
        ).also {
            LyricsTranslation.rememberTranslation(cacheKey, it)
            LyricsTranslation.writeCache(cacheKey, it)
        }
    }

    private fun geminiInstruction(target: String, count: Int): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val korean = LyricsTranslation.canonicalLanguage(target) == "ko"
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

    private suspend fun requestGemini(slots: List<TextSlot>, target: String): LyricsTranslation.BatchAnswer? {
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
    private suspend fun <T : Any> askGemini(
        body: String,
        client: okhttp3.OkHttpClient = geminiClient,
        models: List<String> = GEMINI_MODELS,
        /** Collects each model's status code, for a caller that retries on what they were. */
        codes: MutableList<Int>? = null,
        parse: (String) -> T?,
    ): T? {
        val key = cleanGeminiKey()
        if (key.isEmpty() || key == rejectedGeminiKey) return null
        if (System.currentTimeMillis() < geminiCooldownUntil) return null
        var quotaRefusals = 0
        for (model in models) {
            val request = Request.Builder()
                .url(GEMINI_ENDPOINT.format(model))
                .header("x-goog-api-key", key)
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
            val (code, text) = try {
                client.newCall(request).awaitResponse()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return null
            }
            codes?.add(code)
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
        if (quotaRefusals == models.size) {
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

    private fun parseGemini(response: String, expected: Int): LyricsTranslation.BatchAnswer? = runCatching {
        val text = geminiText(response)
        val answer = json.parseToJsonElement(text).jsonObject
        val translations = answer["translations"]!!.jsonArray.map {
            it.jsonPrimitive.contentOrNull.orEmpty().trim()
        }
        if (translations.size != expected || translations.all { it.isBlank() }) return@runCatching null
        val source = answer["source_language"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        LyricsTranslation.BatchAnswer(
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

    /**
     * Whether [second] is a destination no translation is needed for. Chinese
     * is compared by script as well as language: zh-CN and zh-TW are two ways
     * of writing one language, and a Simplified sheet asked into Traditional
     * still needs the conversion Google Translate does. Reporting it as
     * "already in this language" would leave the reading in the wrong script.
     */
    internal fun sameLanguage(first: String, second: String): Boolean {
        if (LocaleTags.isChineseTag(first) || LocaleTags.isChineseTag(second)) {
            return LocaleTags.sameLanguageScriptAware(first, second)
        }
        return LyricsTranslation.canonicalLanguage(first) == LyricsTranslation.canonicalLanguage(second)
    }

    /**
     * The language [slots] are already written in, when the script alone says
     * so and it is the one asked for. Only Korean: Hangul is Korean's alone,
     * which no other target's script can claim.
     */
    private fun alreadyInTargetScript(slots: List<TextSlot>, target: String): String? {
        if (LyricsTranslation.canonicalLanguage(target) != "ko") return null
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

    private val meanings = object {
        private val entries = object : LinkedHashMap<String, LyricsTranslation.SongMeaning>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, LyricsTranslation.SongMeaning>) = size > 8
        }

        @Synchronized fun get(key: String): LyricsTranslation.SongMeaning? = entries[key]

        @Synchronized fun put(key: String, value: LyricsTranslation.SongMeaning) {
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
    ): LyricsTranslation.MeaningResult {
        val target = targetLanguageTag.trim().ifBlank { "en" }
        val key = LyricsTranslation.cacheKey("meaning\u0000$trackId", target, emptyList())
        val root = keepDir ?: LyricsTranslation.cacheDir
        meanings.get(key)?.let { return LyricsTranslation.MeaningResult.Ready(it) }
        LyricsTranslation.readStored(root, MEANING_DIRECTORY, key)
            ?.let { text -> runCatching { json.decodeFromString<LyricsTranslation.SongMeaning>(text) }.getOrNull() }
            ?.let {
                meanings.put(key, it)
                return LyricsTranslation.MeaningResult.Ready(it)
            }
        if (!geminiKeyUsable) return LyricsTranslation.MeaningResult.NoKey

        val lyricText = lines
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(MAX_MEANING_LYRIC_CHARS)
        if (lyricText.isBlank()) return LyricsTranslation.MeaningResult.Unavailable

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
            return if (geminiKeyUsable) LyricsTranslation.MeaningResult.Unavailable else LyricsTranslation.MeaningResult.NoKey
        }
        meanings.put(key, answer)
        LyricsTranslation.writeStored(root, MEANING_DIRECTORY, key, json.encodeToString(answer), MAX_MEANING_BYTES)
        return LyricsTranslation.MeaningResult.Ready(answer)
    }

    private fun meaningInstruction(target: String): String {
        val locale = Locale.forLanguageTag(target.replace('_', '-'))
        val languageName = locale.getDisplayName(Locale.ENGLISH).ifBlank { target }
        val korean = LyricsTranslation.canonicalLanguage(target) == "ko"
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

    // ---- A song's name in its own language -----------------------------------

    @Serializable
    private data class NativeNameAnswer(
        val found: Boolean = false,
        val title: String = "",
        val artist: String = "",
    )

    /**
     * The title and artist of a song as its original release prints them,
     * given the way a Korean listener or uploader wrote them in Hangul — "발묘 /
     * 나나호시 관현악단" for "抜錨 / ナナホシ管弦楽団". Lyrics catalogues file a
     * Japanese, Chinese or English song under its own name; a Hangul spelling
     * finds nothing in them.
     */
    suspend fun nativeName(title: String, artist: String): LyricsTranslation.NativeNameResult {
        if (!geminiKeyUsable) return LyricsTranslation.NativeNameResult.Unavailable
        val input = buildJsonObject {
            put("title", title)
            put("artist", artist)
        }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", NATIVE_NAME_INSTRUCTION) })
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
                put("temperature", 0)
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("found") { put("type", "BOOLEAN") }
                        putJsonObject("title") { put("type", "STRING") }
                        putJsonObject("artist") { put("type", "STRING") }
                    }
                    putJsonArray("required") {
                        add("found")
                        add("title")
                        add("artist")
                    }
                }
            }
        }.toString()
        val answer = try {
            askGemini(body) { text ->
                runCatching { json.decodeFromString<NativeNameAnswer>(geminiText(text)) }.getOrNull()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return LyricsTranslation.NativeNameResult.Unavailable
        val foundTitle = answer.title.trim()
        val foundArtist = answer.artist.trim()
        if (!answer.found || foundTitle.isEmpty()) return LyricsTranslation.NativeNameResult.NotForeign
        // Asked for the original and handed the question back: nothing new.
        if (foundTitle == title.trim() && (foundArtist.isEmpty() || foundArtist == artist.trim())) {
            return LyricsTranslation.NativeNameResult.NotForeign
        }
        return LyricsTranslation.NativeNameResult.Found(foundTitle, foundArtist.ifEmpty { artist })
    }

    private val NATIVE_NAME_INSTRUCTION = """
        You identify songs for a lyrics search. The input is a song title and artist as a Korean listener wrote them, usually in Hangul.
        Often the song is not Korean: it is a Japanese, Chinese or English song (J-pop, Vocaloid, anime, C-pop, Western pop) whose names were transliterated or translated into Hangul, for example a Korean cover of it.
        If you are confident which original song this is, return found=true with "title" and "artist" exactly as the original release writes them, in the original script (kanji/kana, Chinese characters, Latin letters). Example: {"title":"발묘","artist":"나나호시 관현악단"} -> {"found":true,"title":"抜錨","artist":"ナナホシ管弦楽団"}.
        If the song is originally Korean, or you are not confident, return found=false with empty strings. Never guess.
    """.trimIndent()

    // ---- When each line is sung in a video ---------------------------------------

    /**
     * Gemini watching the YouTube video [videoId] and saying when each of
     * [lines] starts being sung in it, in milliseconds from the video's start —
     * null for a line it did not hear. Null altogether when Gemini isn't set
     * up or didn't answer in full.
     *
     * The video is handed over as its YouTube address, which Gemini fetches
     * itself: nothing is uploaded from the phone. Video is the slow kind of
     * request, so it runs on its own longer deadline.
     */
    suspend fun placeLinesInVideo(
        videoId: String,
        lines: List<LyricsTranslation.LineToPlace>,
        /** The video's sound as WAV, when the phone has it: heard in seconds rather than fetched and watched. */
        audioWav: ByteArray? = null,
    ): List<Long?>? {
        if (!geminiKeyUsable || lines.isEmpty()) return null
        val input = buildJsonObject {
            put("line_count", lines.size)
            putJsonArray("lines") {
                lines.forEachIndexed { index, line ->
                    add(buildJsonObject {
                        put("i", index)
                        put("ref", clock(line.referenceMs))
                        put("text", line.text)
                    })
                }
            }
        }
        // Quick by default: the video watched at a frame every five seconds
        // (the answer is in the audio, the frames only cost time) and little
        // thinking — the job is hearing, not reasoning. Asked plainly as
        // before if Gemini won't take those settings.
        fun body(quick: Boolean) = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", PLACE_LINES_INSTRUCTION) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(mediaPart(videoId, audioWav, quick))
                        add(buildJsonObject { put("text", input.toString()) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                if (quick) putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("placements") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("i") { put("type", "INTEGER") }
                                    putJsonObject("t") { put("type", "STRING") }
                                }
                                putJsonArray("required") { add("i"); add("t") }
                            }
                        }
                    }
                    putJsonArray("required") { add("placements") }
                }
            }
        }.toString()
        return try {
            // The stronger model first: hearing where a line starts is the one
            // job here the lite models are noticeably worse at. An unknown
            // model or a used-up quota falls through to the next.
            val codes = mutableListOf<Int>()
            val parse = { text: String -> runCatching { parsePlacements(geminiText(text), lines.size) }.getOrNull() }
            askGemini(body(quick = true), geminiVideoClient, models = VIDEO_MODELS, codes = codes, parse = parse)
                ?: if (codes.isNotEmpty() && codes.all { it == 400 }) {
                    askGemini(body(quick = false), geminiVideoClient, models = VIDEO_MODELS, parse = parse)
                } else {
                    null
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private val VIDEO_MODELS = (listOf("gemini-3.5-flash") + GEMINI_MODELS).distinct()

    /**
     * What Gemini listens to: the video's sound sent along (16 kHz WAV —
     * seconds to take in), or the YouTube address for it to fetch and watch,
     * which is what made these requests take a minute.
     */
    private fun mediaPart(videoId: String, audioWav: ByteArray?, quick: Boolean) = buildJsonObject {
        if (audioWav != null) {
            putJsonObject("inlineData") {
                put("mimeType", "audio/wav")
                put("data", java.util.Base64.getEncoder().encodeToString(audioWav))
            }
        } else {
            putJsonObject("fileData") {
                put("fileUri", "https://www.youtube.com/watch?v=$videoId")
                put("mimeType", "video/*")
            }
            if (quick) putJsonObject("videoMetadata") { put("fps", VIDEO_FPS) }
        }
    }

    // ---- Lyrics heard off a video ------------------------------------------------

    /**
     * Gemini listening to the YouTube video [videoId] and writing down what
     * is sung, line by line, with when each line starts — for a video no
     * lyrics source has anything for. An empty list when nothing is sung;
     * null when Gemini isn't set up, refused or didn't answer usably.
     */
    suspend fun transcribeVideo(videoId: String, durationMs: Long, audioWav: ByteArray? = null): List<LyricLine>? {
        if (!geminiKeyUsable || videoId.isBlank()) return null
        fun body(quick: Boolean) = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", TRANSCRIBE_INSTRUCTION) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(mediaPart(videoId, audioWav, quick))
                        add(buildJsonObject { put("text", "Video length: ${clock(durationMs)}") })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("mediaResolution", "MEDIA_RESOLUTION_LOW")
                put("maxOutputTokens", 8192)
                if (quick) putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("sung") { put("type", "BOOLEAN") }
                        putJsonObject("lines") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("t") { put("type", "STRING") }
                                    putJsonObject("text") { put("type", "STRING") }
                                }
                                putJsonArray("required") { add("t"); add("text") }
                            }
                        }
                    }
                    putJsonArray("required") { add("sung"); add("lines") }
                }
            }
        }.toString()
        return try {
            val codes = mutableListOf<Int>()
            val parse = { text: String -> runCatching { parseTranscript(geminiText(text), durationMs) }.getOrNull() }
            askGemini(body(quick = true), geminiVideoClient, models = VIDEO_MODELS, codes = codes, parse = parse)
                ?: if (codes.isNotEmpty() && codes.all { it == 400 }) {
                    askGemini(body(quick = false), geminiVideoClient, models = VIDEO_MODELS, parse = parse)
                } else {
                    null
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `{"sung":true,"lines":[{"t":"0:12.4","text":"…"}, …]}` as timed lines in
     * order — an empty list for `"sung":false`; null for an answer too thin or
     * too disordered to show (under three lines, or times running backwards
     * for more than a few of them).
     */
    internal fun parseTranscript(text: String, durationMs: Long): List<LyricLine>? {
        val root = json.parseToJsonElement(text).jsonObject
        val sung = root["sung"]?.jsonPrimitive?.booleanOrNull
        val items = root["lines"]?.jsonArray.orEmpty()
        val limit = if (durationMs > 0L) durationMs + 5_000L else Long.MAX_VALUE
        val lines = items.mapNotNull { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val words = obj["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val at = obj["t"]?.jsonPrimitive?.contentOrNull?.let(::parseClock)?.takeIf { it <= limit } ?: return@mapNotNull null
            LyricLine(timeMs = at, text = words)
        }
        if (lines.isEmpty()) return if (sung == false) emptyList() else null
        val backwards = lines.zipWithNext().count { (a, b) -> b.timeMs < a.timeMs }
        if (lines.size < 3 || backwards > lines.size / 10 + 1) return null
        return lines.sortedBy { it.timeMs }
            .distinctBy { it.timeMs to it.text }
            // A line held at 0:00 is a header the model wrote out, not something sung.
            .let { sorted -> if (sorted.size > 3 && sorted.first().timeMs == 0L && sorted[1].timeMs > 15_000L) sorted.drop(1) else sorted }
    }

    private val TRANSCRIBE_INSTRUCTION = """
        You write down the lyrics sung in a video so they can be shown in time with it. The video is someone singing a song: an official video, a cover, a live take or a clip from a stream.
        Listen to the audio and write each lyric line exactly as it is sung, in the language and script it is sung in: Korean in Hangul, Japanese in its usual kanji and kana, English in English, and so on. Do not translate, romanize, summarize or correct the singer.
        Break the lyrics into lines the way a lyric sheet does, a phrase of a few seconds each, and give for each line the moment its first word starts being sung, as M:SS.s counted from the very start of the video (0:00.0). Write a chorus out again every time it is sung. Leave out talking, chat, announcements and parts with no singing.
        Only write words you actually hear. If a stretch is impossible to make out, leave it out rather than guessing, and never fill in lines from memory that are not sung in this video.
        Return JSON {"sung":true,"lines":[{"t":"0:12.4","text":"…"},…]} in time order. If nothing is sung in the video, return {"sung":false,"lines":[]}.
    """.trimIndent()

    /** Frames a second Gemini looks at when placing lines; see [placeLinesInVideo]. */
    private const val VIDEO_FPS = 0.2

    /**
     * `{"placements":[{"i":0,"t":"0:12.4"}, …]}` as one start per line (ms),
     * null where a line wasn't placed. Read by index, not position: a model
     * that skips a line or adds one must not shift every answer after it.
     * Takes "M:SS.s" as asked and plain seconds as models sometimes answer.
     */
    internal fun parsePlacements(text: String, count: Int): List<Long?>? {
        val items = json.parseToJsonElement(text).jsonObject["placements"]?.jsonArray ?: return null
        val starts = arrayOfNulls<Long>(count)
        for (item in items) {
            val obj = item.jsonObject
            val index = obj["i"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: continue
            if (index !in 0 until count) continue
            val ms = parseClock(obj["t"]?.jsonPrimitive?.contentOrNull ?: continue) ?: continue
            if (starts[index] == null) starts[index] = ms
        }
        return starts.toList().takeIf { list -> list.count { it != null } >= 2 }
    }

    /** "1:23.4", "01:23", "83.4" → ms; null for "-", "", a negative or anything else. */
    internal fun parseClock(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("-")) return null
        val parts = t.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for ((n, part) in parts.withIndex()) {
            val value = part.trim().toDoubleOrNull() ?: return null
            if (value < 0) return null
            if (n < parts.lastIndex && (value != Math.floor(value))) return null
            seconds = seconds * 60 + value
        }
        return (seconds * 1000).toLong()
    }

    /** ms → "M:SS.s", the way the model reads and writes video time best. */
    private fun clock(ms: Long): String {
        val tenths = (ms + 50) / 100
        val minutes = tenths / 600
        val rest = tenths % 600
        return "%d:%02d.%d".format(Locale.ROOT, minutes, rest / 10, rest % 10)
    }

    private val geminiVideoClient by lazy {
        Http.client.newBuilder()
            // Gemini fetches and watches the whole video before it answers.
            .callTimeout(150, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    private val PLACE_LINES_INSTRUCTION = """
        You time song lyrics to a video. The video is someone singing a song (often a cover, a live take or a stream clip). You get the song's lyric lines in order with an index "i"; "ref" is when each line starts in the original studio recording, as M:SS.s.
        Listen to the video's audio and find, for each line, the moment in THIS video when the singer starts singing its first word. Give that moment as M:SS.s counted from the very start of the video (0:00.0). The video may begin earlier or later than the recording (an intro, talking), may run slightly faster or slower, and may leave parts out, so use "ref" only for the order and the spacing of lines; never copy it or add a fixed amount to it.
        Return JSON {"placements":[{"i":0,"t":"0:12.4"},...]} with one entry per line you actually hear sung, in order. Leave out lines that are not sung in this video. Times must never decrease. Only give a time you heard; do not guess.
    """.trimIndent()

    private fun parseMeaning(response: String): LyricsTranslation.SongMeaning? = runCatching {
        val parsed = json.decodeFromString<LyricsTranslation.SongMeaning>(geminiText(response))
        val cleaned = parsed.copy(
            summary = parsed.summary.trim(),
            meaning = parsed.meaning.trim(),
            highlights = parsed.highlights
                .map { LyricsTranslation.MeaningHighlight(it.line.trim(), it.note.trim()) }
                .filter { it.line.isNotEmpty() && it.note.isNotEmpty() }
                .take(4),
        )
        cleaned.takeIf { it.summary.isNotEmpty() && it.meaning.isNotEmpty() }
    }.getOrNull()
}
