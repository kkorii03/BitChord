package com.music.bitchord.playback.smart

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Word times a GitHub runner worked out ahead of time (bench/farm/farm.py: wav2vec2 CTC on the
 * video's own audio, median line error 36 ms on Jamendo against 306 ms for stable-ts). They land
 * in [SongHearing]'s kept transcripts, so a song the farm knows needs no request, no wait and no
 * battery. One GET per video per run; a song it does not know goes on as before.
 */
internal object AlignFarm {
    private const val TAG = "AlignFarm"
    private const val BASE = "https://raw.githubusercontent.com/kkorii03/BitChord/align-data/"

    /** Keeps lyric text out of a public branch; nothing stronger. Same as farm.py's FARM_DATA_PASS. */
    private const val PASS = "bitchord-align-farm-1"

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

    /** SHA-256 counter keystream, the same both ways. */
    internal fun decipher(data: ByteArray, password: String = PASS): ByteArray {
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
    internal fun savedFrom(bytes: ByteArray, language: String): SongHearing.Saved? {
        val result = runCatching { json.decodeFromString(Result.serializer(), decipher(bytes).decodeToString()) }.getOrNull()
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

    /** What the farm holds for [videoId], asked once a run; null on anything but a usable answer. */
    suspend fun fetch(videoId: String, language: String): SongHearing.Saved? {
        if (!asked.add(videoId)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url("$BASE$videoId.enc").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    savedFrom(response.body?.bytes() ?: return@use null, language)
                }
            }.onFailure { TrackLog.d(TAG, "align-farm: ${it.javaClass.simpleName}", videoId) }.getOrNull()
                ?.also { TrackLog.i(TAG, "align-farm: ${it.words.size} words lang=$language", videoId) }
        }
    }
}
