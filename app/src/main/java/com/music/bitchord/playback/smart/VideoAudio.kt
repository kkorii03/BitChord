package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.PowerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
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

    /** Both decoded-audio caches together stay under this, oldest evicted first. */
    // 16 MB: one whole 7-minute clip (13.4 MB) still fits, so the AI timing and
    // the AI lyrics asking for the same song do not decode it twice.
    private const val MAX_CACHED_AUDIO_BYTES = 16 * 1024 * 1024

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

    /**
     * How long a song check waits, off the decode gate, for its opening to be
     * on disk: a stream fills at about playback speed, so the ninety-second
     * opening of a track that just started takes about that long.
     */
    private const val OPENING_WAIT_MS = 120_000L

    /**
     * Sound read from one copy, with the rendition it came from. The clock a
     * transcript belongs to is the copy that was actually opened, so it is
     * captured here rather than re-asked after the listen.
     */
    data class SongAudio(
        val bytes: ByteArray,
        /** The cache rendition key, or null when read straight off a local file. */
        val renditionKey: String?,
        val fromFile: Boolean,
        /** The verified source pair recorded for a local file, captured when it was opened. */
        val localSourceConfigId: String? = null,
        val localTrackId: String? = null,
    )

    /**
     * The AI timing and the AI lyrics often ask for the same song. A long
     * session can otherwise keep every decoded clip: 11-02's heap report had
     * 11 MB of them. One access-ordered cache with an 8 MB shared budget
     * covers both the video WAVs and the song-check openings.
     */
    private val recent = ByteLru<String, SongAudio>(MAX_CACHED_AUDIO_BYTES) { it.bytes.size }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val songLocks = ConcurrentHashMap<String, Mutex>()

    private fun videoKey(videoId: String) = "video\u0000$videoId"
    private fun songKey(key: String) = "song\u0000$key"

    suspend fun wav(context: Context, videoId: String): ByteArray? =
        wavCopy(context, videoId)?.bytes

    /** [wav] with the rendition it was read from, so its clock is not guessed afterwards. */
    suspend fun wavCopy(context: Context, videoId: String): SongAudio? = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        recent.get(videoKey(videoId))?.let { return@withContext it }
        if (!PowerState.screenOn) {
            TrackLog.d(TAG, "AI timing: the screen is off", videoId)
            return@withContext null
        }
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        AudioCache.keepForTiming(videoId)
        if (!AudioCache.awaitAnalysisTrack(opus, FETCH_TIMEOUT_MS)) {
            TrackLog.d(TAG, "AI timing: no analysis audio on disk", videoId)
            return@withContext null
        }
        val copy = VersionAudioAligner.renditionToMeasure(videoId)
        if (copy == null) {
            TrackLog.d(TAG, "AI timing: no cache copy to decode", videoId)
            return@withContext null
        }
        if (!copy.isComplete) {
            TrackLog.d(
                TAG,
                "AI timing: copy is still ${copy.cachedPrefix}/${copy.contentLength} bytes",
                videoId,
            )
            return@withContext null
        }
        locks.getOrPut(videoId) { Mutex() }.withLock {
            recent.get(videoKey(videoId))?.let { return@withLock it }
            // A cancelled caller must stop waiting at AudioDecoder's gate at
            // once; runInterruptible turns the cancellation into an interrupt
            // of the blocking decode instead of leaving it queued for minutes.
            val audio = AudioDecoder.interruptibleDecode { decode(videoId, copy) { job?.isActive != false } }
                ?: return@withLock null
            recent.put(videoKey(videoId), audio)
            audio
        }
    }

    /**
     * Whether [wav] can get this song's sound for [videoId] right now. The AI
     * timing action uses this to stay off when Deepgram is the only engine set
     * up and there is nothing for it to hear.
     *
     * This must ask exactly what [wav] asks. A freshly streamed track is
     * usually still partial — the player only holds the window around the
     * playhead — and [wav] needs the copy it decodes to be on disk in full, so
     * offering the action for a merely in-progress rendition only ends in
     * `aiFailed`. The earlier change that accepted any cached bytes did
     * exactly that: on an ordinary stream the button appeared, then `wav`
     * returned null. Readiness is therefore the same *chosen* copy
     * ([VersionAudioAligner.chooseMeasurementCopy]) being complete.
     */
    fun audioReadyForTiming(videoId: String): Boolean {
        val cached = recent.get(videoKey(videoId)) != null
        if (cached) return true
        val screenOn = PowerState.screenOn
        if (!screenOn) return false
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        return timingAudioReady(
            recentCached = false,
            screenOn = screenOn,
            onDisk = AudioCache.renditionsOf(watch),
        )
    }

    /**
     * [audioReadyForTiming]'s rule with the cache already read, so the
     * readiness/`wav` contract can be tested without an Android cache.
     *
     * [wav] decodes [VersionAudioAligner.renditionToMeasure] only when that
     * copy is complete, so both must name the same copy and require its
     * completeness. A partial same-source opening is picked over a complete
     * substitute whenever it reaches the measurement floor, and `wav` then
     * fails on it too; matching the pick here keeps the button off in that
     * case.
     */
    internal fun timingAudioReady(
        recentCached: Boolean,
        screenOn: Boolean,
        onDisk: List<AudioCache.Rendition>,
    ): Boolean {
        if (recentCached) return true
        if (!screenOn) return false
        val copy = VersionAudioAligner.chooseMeasurementCopy(onDisk) ?: return false
        return copy.isComplete
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
    ): ByteArray? = songWavCopy(context, song, untilSeconds)?.bytes

    /** [songWav] with the copy it actually opened, so the transcript is tagged with that clock. */
    suspend fun songWavCopy(
        context: Context,
        song: Song,
        untilSeconds: Double = SONG_CHECK_SECONDS,
    ): SongAudio? = withContext(Dispatchers.IO) {
        if (!untilSeconds.isFinite() || untilSeconds <= 0.0) return@withContext null
        val until = untilSeconds.coerceAtMost(MAX_SECONDS)
        val job = coroutineContext[Job]
        val key = songCacheKey(song, until)
        recent.get(songKey(key))?.let { return@withContext it }
        // Runs with the screen off too (11-01 user decision): this opening is
        // what the song check and the quality-upgrade comparison listen to.
        // Skipping it in a pocket let a wrong song play unchecked, and the
        // listener then spent more battery on the screen fixing it by hand.
        // It is one bounded opening per track, not the whole video.
        val opened = openSongSource(context, song, until) ?: return@withContext null
        try {
            songLocks.getOrPut(key) { Mutex() }.withLock {
                recent.get(songKey(key))?.let {
                    runCatching { opened.data.close() }
                    return@withLock it
                }
                val bytes = AudioDecoder.interruptibleDecode {
                    decodeSong(song, opened.data, opened.fromFile, until) { job?.isActive != false }
                } ?: return@withLock null
                val audio = SongAudio(
                    bytes,
                    opened.renditionKey,
                    opened.fromFile,
                    opened.localSourceConfigId,
                    opened.localTrackId,
                )
                recent.put(songKey(key), audio)
                audio
            }
        } catch (cancelled: CancellationException) {
            // Cancelled while waiting for another decode of this song: the
            // source opened above was never handed to decodeSong, which closes
            // its own in a finally.
            runCatching { opened.data.close() }
            throw cancelled
        }
    }

    private class SongSource(
        val data: MediaDataSource,
        val fromFile: Boolean,
        val renditionKey: String?,
        val localSourceConfigId: String? = null,
        val localTrackId: String? = null,
    )

    private suspend fun openSongSource(context: Context, song: Song, untilSeconds: Double): SongSource? {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val fromFile = local != null && LocalAudioSource.isLocal(local)
        if (!fromFile) {
            // The opening is all a song check reads, so wait for that rather
            // than for the whole track the way the video WAV has to.
            val opus = Uri.parse(AutomixAnalysisSource.opusUri(song.videoId))
            AudioCache.keepForTiming(song.videoId)
            if (!AudioCache.awaitAnalysisHead(opus, HEAD_TIMEOUT_MS)) {
                // A blocked video, or one already swapped to its quality copy,
                // never gets its YouTube opening: listen to the copy that plays
                // instead. Its key tags the words with that copy's clock.
                val substitute = VersionAudioAligner.renditionToMeasure(song.videoId)
                if (substitute == null ||
                    substitute.cachedPrefix < AudioCache.MIN_ALIGNMENT_PREFIX_BYTES
                ) {
                    TrackLog.d(TAG, "song check: the opening is not on disk yet", song.videoId)
                    return null
                }
                TrackLog.d(TAG, "song check: no YouTube opening; listening to ${substitute.key}", song.videoId)
            }
        }
        if (fromFile) {
            // Read the file's clock here, when its data source is opened. Asking
            // again after the ninety-second decode could tag the words with a
            // repair that landed meanwhile.
            val meta = KoDownloadRecords.savedMetadataFor(song.videoId)
            val data = VersionAudioAligner.openDataSource(context, song)
            if (data == null) {
                TrackLog.d(TAG, "song check: no audio to listen to", song.videoId)
                return null
            }
            return SongSource(
                data,
                true,
                null,
                meta?.hqSourceConfigId,
                meta?.hqTrackId,
            )
        }
        // The decode holds AudioDecoder's one-at-a-time gate, and reading past
        // what is on disk there waits for the stream at playback speed: song
        // checks held the gate 70-130 s each on the 11-02 smoke while hq-verify,
        // the next track's sync and automix queued behind them. Wait for the
        // opening's bytes here instead, off the gate and on cache events. Past
        // the wait the decode goes ahead as before, so no check is lost.
        VersionAudioAligner.renditionToMeasure(song.videoId)?.let { first ->
            val totalMs = song.durationMillis()
            if (!first.isComplete && first.contentLength > 0 && totalMs > 0) {
                val need = (first.contentLength * ((untilSeconds + 1.0) * 1000.0 / totalMs))
                    .toLong().coerceAtMost(first.contentLength)
                if (first.cachedPrefix < need &&
                    !AudioCache.awaitRenditionPrefix(song.videoId, need, OPENING_WAIT_MS)
                ) {
                    TrackLog.d(TAG, "song check: opening still downloading; decoding as it comes", song.videoId)
                }
            }
        }
        // Pick once and open that exact copy: asking again after the listen
        // could name a different rendition than the one whose words were heard.
        val copy = VersionAudioAligner.renditionToMeasure(song.videoId) ?: return null
        val data = AudioCache.renditionDataSource(
            Uri.parse("bitchord://watch?v=${song.videoId}"),
            copy,
        )
        if (data == null) {
            TrackLog.d(TAG, "song check: no audio to listen to", song.videoId)
            return null
        }
        return SongSource(data, false, copy.key)
    }

    private fun songCacheKey(song: Song, untilSeconds: Double): String =
        "${song.videoId}\u0000${song.localUri.orEmpty()}\u0000$untilSeconds"

    private fun decode(
        videoId: String,
        copy: AudioCache.Rendition,
        /** False once the caller has gone, so a request still queued at the decode gate is dropped. */
        wanted: () -> Boolean,
    ): SongAudio? {
        if (!PowerState.screenOn) return null
        val source = AudioCache.renditionDataSource(
            Uri.parse("bitchord://watch?v=$videoId"),
            copy,
        ) ?: return null
        val resampler = Resampler(RATE * 2 * 240)
        try {
            AudioDecoder.decodeMonoStream(
                source,
                0.0,
                MAX_SECONDS + 1,
                abort = { !PowerState.screenOn || !wanted() },
                label = "video-wav",
            ) { samples, sampleRate, _ ->
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
        return SongAudio(header(data.size) + data, copy.key, fromFile = false)
    }

    /**
     * Sync-test only: the first [untilSeconds] of an audio [file] as the same
     * mono WAV a song check sends — for a downloaded hi-res candidate head, so
     * a phone scoring run can hear that copy's own clock. See
     * [SyncTestMode.keepCandidate].
     */
    internal fun wavOfFile(file: File, untilSeconds: Double = SONG_CHECK_SECONDS): ByteArray? {
        val resampler = Resampler(minOf(RATE * 2 * untilSeconds.toInt(), RATE * 2 * 120))
        try {
            AudioDecoder.decodeMonoStream(file, 0.0, untilSeconds + 1, label = "synctest-wav") { samples, sampleRate, _ ->
                if (!resampler.push(samples, sampleRate)) return@decodeMonoStream
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        val pcm = resampler.bytes()
        if (pcm.size < RATE * 2 * 10) return null
        val limit = (RATE * 2 * untilSeconds).toInt().let { it - it % 2 }
        val data = if (pcm.size > limit) pcm.copyOf(limit) else pcm
        return header(data.size) + data
    }

    private fun decodeSong(
        song: Song,
        source: MediaDataSource,
        fromFile: Boolean,
        untilSeconds: Double,
        /** False once the caller has gone; see [decode]. */
        wanted: () -> Boolean,
    ): ByteArray? {
        // One second past the window, then cut at the sample: a decoded chunk
        // that starts inside the window may run past its end. Not stopped by
        // the screen going off; see [songWav].
        val resampler = Resampler(minOf(RATE * 2 * untilSeconds.toInt(), RATE * 2 * 120))
        try {
            AudioDecoder.decodeMonoStream(
                source,
                0.0,
                untilSeconds + 1,
                abort = { !wanted() },
                label = "video-wav",
            ) { samples, sampleRate, _ ->
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
    internal class Resampler(private val initialBytes: Int) {
        // Sized at the first decoded chunk, not here: a request is made before
        // AudioDecoder's one-at-a-time gate and can wait there for minutes.
        // Allocated up front, 16 waiting requests held 90 MB of a 165 MB heap
        // (11-02 smoke heap report) until playback died of OutOfMemoryError.
        private var buffer: ByteArrayOutputStream? = null
        private val pcm: ByteArrayOutputStream
            get() = buffer ?: ByteArrayOutputStream(initialBytes).also { buffer = it }
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

        fun bytes(): ByteArray = buffer?.toByteArray() ?: ByteArray(0)
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

/**
 * Access-ordered LRU whose values share a byte budget. Reads promote, writes
 * evict the oldest until the total fits — including a single value larger
 * than the budget, which is simply returned by the caller and not cached.
 */
internal class ByteLru<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        // Too big for the whole budget: not cached, and nothing else thrown out for it.
        if (sizeOf(value) > maxBytes) return
        entries[key] = value
        bytes += sizeOf(value)
        while (bytes > maxBytes) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            bytes -= sizeOf(eldest.value)
        }
    }

    @Synchronized
    fun sizeBytes(): Int = bytes

    @Synchronized
    fun size(): Int = entries.size
}
