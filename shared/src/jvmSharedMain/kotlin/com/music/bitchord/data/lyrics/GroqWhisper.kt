package com.music.bitchord.data.lyrics

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Groq's Whisper, for the words of a song's opening when someone is waiting on them.
 *
 * Deepgram's Whisper stalls about 66 s on 18% of requests whatever the length or format,
 * and a second copy sent 8 s later stalled alongside it 7 times of 7 (11-07,
 * bench/whisper_conn_bench.py). Groq answered ninety seconds in 0.8-4.2 s, 32 of 32.
 *
 * The key ([apiKey]) is baked into this personal build, like Deepgram's. With none, or on
 * any failure, the caller goes on to Deepgram.
 */
object GroqWhisper {

    private const val TAG = "GroqWhisper"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3"

    /** Long enough for a slow upload of ninety seconds; Deepgram is still there after it. */
    private const val CALL_TIMEOUT_SECONDS = 25L

    @Volatile
    var apiKey: String = ""

    /** The endpoint [listen] posts to; a test points this at a local server. */
    internal var endpoint: String = ENDPOINT

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        Http.client.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    /** The words heard in [audio], timed, in the same shape Deepgram answers with. */
    suspend fun listen(audio: ByteArray, language: String, mime: String): Deepgram.Answer {
        val key = apiKey.trim()
        if (key.isEmpty()) return Deepgram.Answer(null)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("temperature", "0")
            .apply { if (language != "multi") addFormDataPart("language", language) }
            .addFormDataPart("file", fileName(mime), audio.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(form)
            .build()
        return try {
            with(Deepgram) {
                client.newCall(request).runCancellable { response ->
                    if (!response.isSuccessful) {
                        return@runCancellable Deepgram.Answer(null, response.code)
                    }
                    val body = response.body?.string() ?: return@runCancellable Deepgram.Answer(null, response.code)
                    Deepgram.Answer(parse(body), response.code, audioMs = parseAudioMs(body))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The message names the host at most; the key is only ever a header.
            Log.w(TAG, "listen: no reply: ${e::class.simpleName}: ${e.message?.take(120)}")
            Deepgram.Answer(null)
        }
    }

    /** Groq reads the container off the file name. */
    internal fun fileName(mime: String): String = when {
        "webm" in mime -> "audio.webm"
        "mp4" in mime || "m4a" in mime -> "audio.m4a"
        "ogg" in mime || "opus" in mime -> "audio.ogg"
        "mpeg" in mime || "mp3" in mime -> "audio.mp3"
        "flac" in mime -> "audio.flac"
        else -> "audio.wav"
    }

    /** `words[]`: word, start, end (seconds). */
    internal fun parse(body: String): List<LyricsWordAlign.Heard>? = runCatching {
        json.parseToJsonElement(body).jsonObject["words"]!!.jsonArray.mapNotNull { item ->
            val word = item.jsonObject
            val text = word["word"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: start
            LyricsWordAlign.Heard(text, (start * 1000).toLong(), (end * 1000).toLong())
        }
    }.getOrNull()

    /** `duration` in ms: how much of the sent bytes the server could read. */
    internal fun parseAudioMs(body: String): Long? = runCatching {
        json.parseToJsonElement(body).jsonObject["duration"]?.jsonPrimitive?.doubleOrNull
            ?.takeIf { it > 0.0 }?.let { (it * 1000).toLong() }
    }.getOrNull()
}
