package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
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

    /**
     * How much of a song's opening a sync check listens to. Ninety seconds is
     * the same reasoning as the measurement's opening windows: an intro and a
     * first verse are enough to fit a pace, where a few seconds can only ever
     * say "a shift, if enough words happen to be caught".
     */
    const val SONG_CHECK_SECONDS = 90.0

    /** How long a song check waits for its opening to reach the disk. */
    private const val HEAD_TIMEOUT_MS = 20_000L

    /** The last two, by video: the AI timing and the AI lyrics often ask for the same song. */
    private val recent = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 2
    }
    private val locks = ConcurrentHashMap<String, Any>()

    /** The last two song checks, by recording, file and length — a retry asks again. */
    private val recentSongs = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 2
    }
    private val songLocks = ConcurrentHashMap<String, Any>()

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

    /**
     * The opening of [song] — by default [SONG_CHECK_SECONDS] — as a 16 kHz
     * mono WAV, so a record-timed song's lyrics can be checked against the
     * recording that is playing.
     *
     * Read straight off a downloaded file when there is one (no network at
     * all), otherwise off the copy the player is streaming, waiting only for
     * the opening rather than the whole track. Null when there is nothing to
     * listen to; every refusal is logged against the track, without lyrics
     * text or keys.
     *
     * No caller yet: this is the audio supply for the song check.
     */
    suspend fun songWav(
        context: Context,
        song: Song,
        untilSeconds: Double = SONG_CHECK_SECONDS,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!untilSeconds.isFinite() || untilSeconds <= 0.0) return@withContext null
        val until = untilSeconds.coerceAtMost(MAX_SECONDS)
        val key = songCacheKey(song, until)
        synchronized(recentSongs) { recentSongs[key] }?.let { return@withContext it }
        val opened = openSongSource(context, song) ?: return@withContext null
        synchronized(songLocks.getOrPut(key) { Any() }) {
            synchronized(recentSongs) { recentSongs[key] }?.let {
                runCatching { opened.data.close() }
                return@synchronized it
            }
            val bytes = decodeSong(song, opened.data, opened.fromFile, until) ?: return@synchronized null
            synchronized(recentSongs) { recentSongs[key] = bytes }
            bytes
        }
    }

    private class SongSource(val data: MediaDataSource, val fromFile: Boolean)

    private suspend fun openSongSource(context: Context, song: Song): SongSource? {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val fromFile = local != null && LocalAudioSource.isLocal(local)
        if (!fromFile) {
            // The opening is all a song check reads, so wait for that rather
            // than for the whole track the way the video WAV has to.
            val opus = Uri.parse(AutomixAnalysisSource.opusUri(song.videoId))
            AudioCache.keepForTiming(song.videoId)
            if (!AudioCache.awaitAnalysisHead(opus, HEAD_TIMEOUT_MS)) {
                TrackLog.d(TAG, "song check: the opening is not on disk yet", song.videoId)
                return null
            }
        }
        val data = VersionAudioAligner.openDataSource(context, song)
        if (data == null) {
            TrackLog.d(TAG, "song check: no audio to listen to", song.videoId)
            return null
        }
        return SongSource(data, fromFile)
    }

    private fun songCacheKey(song: Song, untilSeconds: Double): String =
        "${song.videoId}\u0000${song.localUri.orEmpty()}\u0000$untilSeconds"

    private fun decode(context: Context, videoId: String): ByteArray? {
        val source = VersionAudioAligner.openDataSource(
            context,
            Song(videoId = videoId, title = "", artist = "", thumbnailUrl = null, isVideo = true),
        ) ?: return null
        val resampler = Resampler(RATE * 2 * 240)
        try {
            AudioDecoder.decodeMonoStream(source, 0.0, MAX_SECONDS + 1) { samples, sampleRate, _ ->
                if (!resampler.push(samples, sampleRate)) return@decodeMonoStream
            } ?: return null
        } catch (e: Exception) {
            TrackLog.w(TAG, "could not read the audio: ${e.message}", videoId)
            return null
        } finally {
            runCatching { source.close() }
        }
        val data = resampler.bytes()
        if (data.size < RATE * 2 * 10) return null
        if (data.size > RATE * 2 * MAX_SECONDS) {
            TrackLog.d(TAG, "too long to send inline", videoId)
            return null
        }
        return header(data.size) + data
    }

    private fun decodeSong(
        song: Song,
        source: MediaDataSource,
        fromFile: Boolean,
        untilSeconds: Double,
    ): ByteArray? {
        // One second past the window, then cut at the sample: a decoded chunk
        // that starts inside the window may run past its end.
        val resampler = Resampler(minOf(RATE * 2 * untilSeconds.toInt(), RATE * 2 * 120))
        try {
            AudioDecoder.decodeMonoStream(source, 0.0, untilSeconds + 1) { samples, sampleRate, _ ->
                if (!resampler.push(samples, sampleRate)) return@decodeMonoStream
            } ?: return null
        } catch (e: Exception) {
            TrackLog.w(TAG, "song check: could not read the audio: ${e.message}", song.videoId)
            return null
        } finally {
            runCatching { source.close() }
        }
        val pcm = resampler.bytes()
        if (pcm.size < RATE * 2 * 10) {
            TrackLog.d(TAG, "song check: under ten seconds of audio", song.videoId)
            return null
        }
        val limit = (RATE * 2 * untilSeconds).toInt().let { it - it % 2 }
        val data = if (pcm.size > limit) pcm.copyOf(limit) else pcm
        TrackLog.d(
            TAG,
            "song check: ${if (fromFile) "file" else "stream"} " +
                "${data.size / (RATE * 2)}s ${data.size / 1024}KB",
            song.videoId,
        )
        return header(data.size) + data
    }

    /**
     * Mono float chunks written out as 16-bit little-endian at [RATE], each
     * output sample the mean of the input samples in its slot — a box filter,
     * enough to keep the singing clear of aliasing.
     */
    private class Resampler(initialBytes: Int) {
        private val pcm = ByteArrayOutputStream(initialBytes)
        private var carry = 0.0
        private var sum = 0.0
        private var count = 0
        private var lastRate = 0

        /** Appends [samples]; false once a different sample rate appears, so the caller stops. */
        fun push(samples: FloatArray, sampleRate: Int): Boolean {
            if (lastRate != 0 && sampleRate != lastRate) return false
            lastRate = sampleRate
            val step = sampleRate.toDouble() / VideoAudio.RATE
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
            return true
        }

        fun bytes(): ByteArray = pcm.toByteArray()
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
