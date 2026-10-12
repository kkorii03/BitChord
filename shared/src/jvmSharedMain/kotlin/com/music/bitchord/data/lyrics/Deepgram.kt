package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    /**
     * The words heard in [audioWav] by [model], timed; null when Deepgram
     * couldn't be asked or didn't answer. Whisper takes no "multi": it is told
     * to work the language out itself instead.
     */
    suspend fun words(audioWav: ByteArray, language: String, model: String = MODEL_NOVA): List<LyricsWordAlign.Heard>? {
        val key = apiKey.trim()
        if (key.isEmpty() || key == rejectedKey) return null
        val spoken = if (model == MODEL_WHISPER && language == "multi") "detect_language=true" else "language=$language"
        val url = "$ENDPOINT?model=$model&$spoken&punctuate=false&smart_format=false&numerals=false"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $key")
            .post(audioWav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        rejectedKey = key
                        keyRejected.value = true
                        return@withContext null
                    }
                    if (!response.isSuccessful) return@withContext null
                    parse(response.body?.string() ?: return@withContext null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
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
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> true
                        response.code == 401 || response.code == 403 -> false
                        else -> null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

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
