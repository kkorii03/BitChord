package com.music.bitchord.data.lyrics

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Deepgram's speech recognition, for when each word of a song is said —
 * the timed words [LyricsWordAlign] lines the lyrics up against. A few
 * seconds for a song, to a hundredth of a second a word, where Gemini takes
 * tens of seconds and places whole lines to about a second.
 *
 * The key ([apiKey]) is baked into this personal build, like Gemini's. The
 * audio is the song's sound the phone already has (16 kHz WAV).
 */
object Deepgram {

    private const val TAG = "Deepgram"
    private const val ENDPOINT = "https://api.deepgram.com/v1/listen"

    /** Fast (a second or two) but deaf to most singing over a band: 9–66 words in a whole pop song. */
    const val MODEL_NOVA = "nova-3"

    /**
     * Whisper as Deepgram hosts it: a minute or two a song, but it hears the
     * singing — 200+ words, 22 of 25 lines placed within a tenth of a second
     * on a real cover, where nova-3 placed 3.
     */
    const val MODEL_WHISPER = "whisper-large"

    /** The key in use — the listener's own from settings, or the one built in. */
    @Volatile
    var apiKey: String = ""
        set(value) {
            field = value
            keyRejected.value = value.isNotBlank() && value.trim() == rejectedKey
        }

    /** A key Deepgram turned away: not asked with again until it changes. */
    @Volatile
    private var rejectedKey: String? = null

    /** True once Deepgram turned the current key away — for the settings row. */
    val keyRejected = kotlinx.coroutines.flow.MutableStateFlow(false)

    val usable: Boolean get() = apiKey.isNotBlank() && apiKey.trim() != rejectedKey

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(240, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    /** The endpoint [listen] posts to; a test points this at a local server. */
    internal var listenEndpoint: String = ENDPOINT

    /**
     * Runs the blocking [Call] on the IO dispatcher and cancels it when the
     * coroutine is cancelled, so a blocked execute/read returns at once
     * instead of waiting out [client]'s 240 s timeout.
     */
    private suspend fun <T> Call.runCancellable(block: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cancel()
                }
            }
            try {
                execute().use(block)
            } finally {
                watchdog.cancel()
            }
        }

    /**
     * What Deepgram answered: the [words] it heard, or null, and the [httpCode]
     * it answered with (null when no response came back at all — offline, a
     * timeout, or a key that was never asked with). Callers tell a real
     * refusal from a request that never completed by the code.
     */
    data class Answer(
        val words: List<LyricsWordAlign.Heard>?,
        val httpCode: Int? = null,
        /** `Retry-After` in ms when the answer carried one; null otherwise. */
        val retryAfterMs: Long? = null,
        /** How much sound the server read (`metadata.duration`); a cut stream may be under what was asked. */
        val audioMs: Long? = null,
    )

    /**
     * The words heard in [audioWav] by [model], timed; null when Deepgram
     * couldn't be asked or didn't answer. Whisper takes no "multi": it is told
     * to work the language out itself instead.
     */
    suspend fun words(audioWav: ByteArray, language: String, model: String = MODEL_NOVA): List<LyricsWordAlign.Heard>? =
        listen(audioWav, language, model).words

    /** As [words], but saying whether Deepgram actually answered, and with which HTTP code. */
    suspend fun listen(
        audioWav: ByteArray,
        language: String,
        model: String = MODEL_NOVA,
        /** The bytes' container: a cached stream's opening is sent as it is, see `SongHearing`. */
        mime: String = "audio/wav",
    ): Answer {
        val key = apiKey.trim()
        if (key.isEmpty() || key == rejectedKey) return Answer(null)
        val spoken = if (model == MODEL_WHISPER && language == "multi") "detect_language=true" else "language=$language"
        val url = "$listenEndpoint?model=$model&$spoken&punctuate=false&smart_format=false&numerals=false"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $key")
            .post(audioWav.toRequestBody(mime.toMediaType()))
            .build()
        return try {
            client.newCall(request).runCancellable { response ->
                if (response.code == 401 || response.code == 403) {
                    rejectedKey = key
                    keyRejected.value = true
                    return@runCancellable Answer(null, response.code)
                }
                if (!response.isSuccessful) {
                    return@runCancellable Answer(
                        null,
                        response.code,
                        retryAfterMs(response.header("Retry-After")),
                    )
                }
                val body = response.body?.string() ?: return@runCancellable Answer(null, response.code)
                Answer(parse(body), response.code, audioMs = parseAudioMs(body))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The message names the host at most; the key is only ever a header.
            Log.w(TAG, "listen: no reply: ${e::class.simpleName}: ${e.message?.take(120)}")
            Answer(null)
        }
    }

    /**
     * Whether Deepgram accepts [key]: true, false (turned away), or null when
     * it couldn't be asked (offline). One cheap request listing the key's projects.
     */
    suspend fun verify(key: String): Boolean? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val request = Request.Builder()
            .url("https://api.deepgram.com/v1/projects")
            .header("Authorization", "Token $trimmed")
            .get()
            .build()
        return try {
            client.newCall(request).runCancellable { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 401 || response.code == 403 -> false
                    else -> null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** `Retry-After` as milliseconds when the server sent a delay in seconds. */
    private fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.times(1000)?.toLong()

    /** `results.channels[0].alternatives[0].words[]`: word, start, end (seconds). */
    internal fun parse(body: String): List<LyricsWordAlign.Heard>? = runCatching {
        val words = json.parseToJsonElement(body).jsonObject["results"]!!.jsonObject["channels"]!!.jsonArray[0]
            .jsonObject["alternatives"]!!.jsonArray[0].jsonObject["words"]!!.jsonArray
        words.mapNotNull { item ->
            val word = item.jsonObject
            val text = word["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: start
            LyricsWordAlign.Heard(text, (start * 1000).toLong(), (end * 1000).toLong())
        }
    }.getOrNull()

    /** `metadata.duration` in ms: how much of the sent bytes the server could read. */
    internal fun parseAudioMs(body: String): Long? = runCatching {
        json.parseToJsonElement(body).jsonObject["metadata"]?.jsonObject
            ?.get("duration")?.jsonPrimitive?.doubleOrNull
            ?.takeIf { it > 0.0 }?.let { (it * 1000).toLong() }
    }.getOrNull()

    /**
     * Which language to listen for, read off the lyrics: the script most of
     * them are in. Latin-script lyrics go to Nova-3's multilingual model,
     * which takes English and the other European languages in one.
     */
    fun languageFor(lines: List<String>): String {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (line in lines) for (c in line) when {
            c in '\uAC00'..'\uD7A3' -> hangul++
            c in '\u3040'..'\u30FF' -> kana++
            c in '\u4E00'..'\u9FFF' -> han++
            c in 'a'..'z' || c in 'A'..'Z' -> latin++
        }
        val total = (hangul + kana + han + latin).coerceAtLeast(1)
        return when {
            hangul * 4 >= total -> "ko"
            kana * 10 >= total -> "ja"
            han * 2 >= total -> "zh"
            english(lines) -> "en"
            else -> "multi"
        }
    }

    /**
     * How many of [lines] a transcript in [language] could place at all.
     *
     * [LyricsWordAlign] places a line by its opening letters, and a Latin
     * transcript ("en"/"multi") never writes Hangul, kana or Han, so a line
     * that opens in one of those can never count as heard. Measured (phone
     * 11-06, NEON rlYOsUfpi3M): a Korean/English sheet listened to in English
     * had 5 of its 17 opening lines start in Hangul, which capped the song
     * check's agreement at ~70% however well the rest lined up (`match=47%`).
     * The CJK languages keep every line: Whisper may write an English phrase
     * in either script there.
     */
    fun spellableLines(lines: List<String>, language: String): Int {
        if (language == "ko" || language == "ja" || language == "zh") return lines.size
        return lines.count { line ->
            val first = LyricsWordAlign.fold(line).firstOrNull() ?: return@count true
            !(first in '\uAC00'..'\uD7A3' || first in '\u1100'..'\u11FF' || first in '\u3130'..'\u318F' ||
                first in '\u3040'..'\u30FF' || first in '\u4E00'..'\u9FFF')
        }
    }

    /** Share (%) of [lines] a transcript in [language] can never place (see [spellableLines]); 0 for CJK. */
    fun mixedPercent(lines: List<String>, language: String): Int =
        if (lines.isEmpty()) 0 else (lines.size - spellableLines(lines, language)) * 100 / lines.size

    /** At least this share of lines in another script makes a Latin-read sheet "mixed". */
    const val MIXED_PERCENT = 20

    /**
     * The language the song check's one retry listens in instead of [language],
     * or null to listen the same way again. Only a mixed sheet switches: read
     * as Latin, yet [MIXED_PERCENT]+ of its lines open in Hangul/kana/Han, it
     * retries in that script's language. Not "multi": Whisper treats it as
     * detect-one-language, which hears the Latin majority again (NEON
     * rlYOsUfpi3M: 5 of 17 Hangul lines, 47%/44% both times in English).
     */
    fun retryLanguageFor(lines: List<String>, language: String): String? {
        if (mixedPercent(lines, language) < MIXED_PERCENT) return null
        val other = languageFor(lines.filter { spellableLines(listOf(it), language) == 0 })
        return other.takeIf { it == "ko" || it == "ja" || it == "zh" }
    }

    /** Latin lyrics that read as English: its commonest little words make up a good share of them. */
    private fun english(lines: List<String>): Boolean {
        val words = lines.flatMap { it.lowercase().split(Regex("[^a-z']+")) }.filter { it.isNotEmpty() }
        if (words.size < 8) return false
        return words.count { it in ENGLISH_WORDS } * 5 >= words.size
    }

    private val ENGLISH_WORDS = setOf(
        "the", "you", "i", "me", "my", "your", "and", "to", "a", "it", "is", "in", "on", "of", "we", "that", "this",
        "be", "love", "don't", "i'm", "it's", "with", "for", "all", "so", "can", "know", "just", "what", "when", "oh",
    )
}
