package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * A video's sound as a small WAV — 16 kHz mono — for Gemini to listen to
 * instead of fetching and watching the whole YouTube video itself: it is
 * the frames and the fetch that make a video request take a minute. Read
 * off the copy the player already has on disk (waiting for the rest of it
 * to come down, if it is still coming), so nothing is fetched twice.
 *
 * Null — and the caller sends the YouTube address as before — when the
 * audio isn't on the device in full, or the song is too long to send inline.
 */
object VideoAudio {

    private const val TAG = "VideoAudio"
    private const val RATE = 16_000

    /** Inline requests are capped at 20 MB; base64 adds a third. Seven minutes stays well under. */
    private const val MAX_SECONDS = 7.0 * 60

    /** How long to wait for the rest of the track to come down. */
    private const val FETCH_TIMEOUT_MS = 60_000L

    /** The last two, by video: the AI timing and the AI lyrics often ask for the same song. */
    private val recent = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 2
    }
    private val locks = ConcurrentHashMap<String, Any>()

    suspend fun wav(context: Context, videoId: String): ByteArray? = withContext(Dispatchers.IO) {
        synchronized(recent) { recent[videoId] }?.let { return@withContext it }
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        AudioCache.keepForTiming(videoId)
        if (!AudioCache.awaitAnalysisTrack(opus, FETCH_TIMEOUT_MS)) return@withContext null
        val copy = VersionAudioAligner.renditionToMeasure(videoId)
        if (copy == null || !copy.isComplete) return@withContext null
        synchronized(locks.getOrPut(videoId) { Any() }) {
            synchronized(recent) { recent[videoId] }?.let { return@synchronized it }
            val bytes = decode(context, videoId) ?: return@synchronized null
            synchronized(recent) { recent[videoId] = bytes }
            bytes
        }
    }

    private fun decode(context: Context, videoId: String): ByteArray? {
        val source = VersionAudioAligner.openDataSource(
            context,
            Song(videoId = videoId, title = "", artist = "", thumbnailUrl = null, isVideo = true),
        ) ?: return null
        val pcm = ByteArrayOutputStream(RATE * 2 * 240)
        var carry = 0.0
        var sum = 0.0
        var count = 0
        var lastRate = 0
        try {
            AudioDecoder.decodeMonoStream(source, 0.0, MAX_SECONDS + 1) { samples, sampleRate, _ ->
                if (lastRate != 0 && sampleRate != lastRate) return@decodeMonoStream
                lastRate = sampleRate
                // Averaged down to 16 kHz: each output sample is the mean of the
                // input samples that fall in its slot — a box filter, enough to
                // keep the singing clear of aliasing.
                val step = sampleRate.toDouble() / RATE
                for (sample in samples) {
                    sum += sample
                    count++
                    carry += 1.0
                    if (carry >= step) {
                        carry -= step
                        val value = (sum / count).coerceIn(-1.0, 1.0)
                        val short = (value * Short.MAX_VALUE).toInt()
                        pcm.write(short and 0xff)
                        pcm.write((short shr 8) and 0xff)
                        sum = 0.0
                        count = 0
                    }
                }
            } ?: return null
        } catch (e: Exception) {
            TrackLog.w(TAG, "could not read the audio: ${e.message}", videoId)
            return null
        } finally {
            runCatching { source.close() }
        }
        val data = pcm.toByteArray()
        if (data.size < RATE * 2 * 10) return null
        if (data.size > RATE * 2 * MAX_SECONDS) {
            TrackLog.d(TAG, "too long to send inline", videoId)
            return null
        }
        return header(data.size) + data
    }

    private fun header(dataBytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + dataBytes)
        put("WAVE".toByteArray())
        put("fmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(RATE)
        putInt(RATE * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(dataBytes)
    }.array()
}
