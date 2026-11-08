package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * A song's words heard ahead of time on a GitHub runner (bench/farm/farm.py). They land in
 * [SongHearing]'s kept transcripts, so a song the farm knows needs no request and no wait, from
 * the first line. One GET per video per run; a song it does not know goes on as before.
 *
 * The files sit in a private R2 bucket behind a Worker that can only read, and only for
 * [readToken] (bench/farm/r2_tool.py). Names and contents are still under the Deepgram key baked
 * into the signed APK, which the repository does not hold. No key, no lookup.
 */
internal object AlignFarm {
    private const val TAG = "AlignFarm"
    private const val BASE = "https://bitchord-align-read.bitchord-align.workers.dev/"
    private const val READ_TOKEN_LABEL = "bitchord-farm-read"

    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        Http.client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    }

    @Serializable
    internal data class Result(
        val lang: String,
        val audio_s: Double,
        val words: List<Word>,
    )

    @Serializable
    internal data class Word(val t: String, val s: Long, val e: Long)

    /** A file name that does not say which video it is. Same as farm.py's data_name. */
    internal fun nameOf(videoId: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    /** What the Worker asks for: read-only, and no R2 key in the APK. Same as r2_tool.py's read_token. */
    internal fun readToken(secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(READ_TOKEN_LABEL.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 counter keystream, the same both ways. Same as farm.py's stream. */
    internal fun decipher(data: ByteArray, password: String): ByteArray {
        val key = password.toByteArray()
        val out = ByteArray(data.size)
        var at = 0
        while (at < data.size) {
            val counter = ByteArray(8) { i -> (at.toLong() ushr (8 * (7 - i))).toByte() }
            val pad = MessageDigest.getInstance("SHA-256").digest(key + counter)
            for (i in 0 until minOf(32, data.size - at)) out[at + i] = (data[at + i].toInt() xor pad[i].toInt()).toByte()
            at += 32
        }
        return out
    }

    /** The farm's words as a kept transcript of the YouTube stream, if they suit [language]. */
    internal fun savedFrom(bytes: ByteArray, language: String, password: String): SongHearing.Saved? {
        val result = runCatching { json.decodeFromString(Result.serializer(), decipher(bytes, password).decodeToString()) }.getOrNull()
            ?: return null
        if (result.lang != language || result.words.isEmpty()) return null
        val clock = LyricsTiming.clockToKey(LyricsTiming.Clock.YouTube) ?: return null
        return SongHearing.Saved(
            recording = SongHearing.streamRecording(clock),
            language = language,
            seconds = result.audio_s,
            whole = true,
            clock = clock,
            words = result.words.map { LyricsTiming.SavedWord(it.t, it.s, it.e) },
        )
    }

    /** GETs that got no reply, per video: each costs a hearing up to the call timeout. */
    private val failures = ConcurrentHashMap<String, Int>()

    /** Whether [videoId] has failed fewer than [MAX_FAILED_ASKS] times; an answered video is still asked once only. */
    internal fun mayAsk(videoId: String): Boolean = (failures[videoId] ?: 0) < MAX_FAILED_ASKS

    /** A GET for [videoId] got no reply: it may be asked again, up to [MAX_FAILED_ASKS] in all. */
    internal fun noteFailure(videoId: String) {
        failures.merge(videoId, 1, Int::plus)
        asked.remove(videoId)
    }

    /**
     * Failed GETs a video may have before the farm is left alone this run: the ask at the
     * track's start, the one after the wait for sound ([SongHearing]) and one on a retry.
     */
    internal const val MAX_FAILED_ASKS = 3

    /** What the farm holds for [videoId], asked once a run; null on anything but a usable answer. */
    suspend fun fetch(app: Context, videoId: String, language: String): SongHearing.Saved? {
        if (!mayAsk(videoId) || !asked.add(videoId)) return null
        val secret = app.getString(com.music.bitchord.R.string.default_deepgram_api_key).trim()
        if (secret.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE${nameOf(videoId, secret)}.enc")
                    .header("x-farm-token", readToken(secret))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    savedFrom(response.body?.bytes() ?: return@use null, language, secret + videoId)
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                // A dropped connection says nothing about the farm: the next hearing may ask again.
                noteFailure(videoId)
                TrackLog.d(TAG, "align-farm: ${it.javaClass.simpleName}", videoId)
            }.getOrNull()
                ?.also { TrackLog.i(TAG, "align-farm: ${it.words.size} words lang=$language", videoId) }
        }
    }
}
