package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.RefineExtendPolicy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.StreamChoice
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
        /** What [bytes] are: the decoded WAV, or a cached stream's own container. */
        val mime: String = "audio/wav",
        /** These bytes run to the end of the recording. */
        val whole: Boolean = false,
        /** About how much sound a cut stream holds; null for a decoded window, which knows. */
        val roughSeconds: Double? = null,
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
    private fun partialKey(key: String) = "partial\u0000$key"

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
     * Whether [copy] already holds the opening up to [untilSeconds], so a song
     * check can read it without waiting for the analysis head first. A playing
     * copy that has the bytes needs no wait at all; asking anyway added the
     * head's whole timeout to a check that could already answer.
     *
     * Unknown length or duration keeps the head wait: there is no way to know
     * the bytes on disk cover the window.
     */
    internal fun hasOpeningOnDisk(
        copy: AudioCache.Rendition?,
        untilSeconds: Double,
        durationMs: Long,
    ): Boolean {
        if (copy == null) return false
        if (copy.isComplete) return true
        val need = openingBytes(copy, untilSeconds, durationMs) ?: return false
        return copy.cachedPrefix >= need
    }

    /**
     * Why a read of a song's opening answered null, for the caller's log.
     *
     * The quality comparison reports a missing reference; naming the cause
     * apart is what tells a busy decoder ([GATE_TIMEOUT]) from a recording too
     * short to compare ([TOO_SHORT]) from one that would not decode ([DECODE]).
     */
    enum class SongWavRefusal(val wire: String) {
        /** No copy or file to open: the opening is not on disk yet. */
        NO_WAV("no-wav"),

        /** The read never got the decoder: it waited at the one-at-a-time gate. */
        GATE_TIMEOUT("gate-timeout"),

        /** The read stopped at a named stage before it had ten seconds. */
        DECODE("decode"),

        /** Less than ten seconds of audio came back. */
        TOO_SHORT("too-short"),
    }

    /**
     * Whether [song]'s opening up to [untilSeconds] is in hand right now, with
     * no wait and no decode: the opening already read for this window, a file
     * that is on the device, or a playing copy that already holds those bytes.
     *
     * The quality comparison starts its read only when this is true. A read
     * started without it spends the analysis head's whole timeout waiting for
     * bytes that are not coming, then queues at the one-at-a-time gate, and the
     * play change kills it with nothing heard — 11-05 auto8b: 24 s waited, zero
     * audio decoded, reported as "no reference reason=cancelled". Waiting for
     * the opening is the song check's job; the comparison only uses what it
     * left behind.
     */
    internal fun referenceReady(song: Song, untilSeconds: Double): Boolean {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val key = songCacheKey(song, untilSeconds)
        return referenceReady(
            cachedOpening = pickOpening(recent.get(songKey(key)), recent.get(partialKey(key)), true) != null,
            copy = VersionAudioAligner.renditionToMeasure(song.videoId),
            isLocalFile = local != null && LocalAudioSource.isLocal(local),
            untilSeconds = untilSeconds,
            durationMs = song.durationMillis(),
        )
    }

    /** [referenceReady]'s rule with its inputs already read, so it can be tested without a cache. */
    internal fun referenceReady(
        cachedOpening: Boolean,
        copy: AudioCache.Rendition?,
        isLocalFile: Boolean,
        untilSeconds: Double,
        durationMs: Long,
    ): Boolean =
        cachedOpening || isLocalFile || hasOpeningOnDisk(copy, untilSeconds, durationMs)

    /**
     * Whether a read that stopped before its window may still be kept.
     *
     * A read the caller walked away from ([failedStage] null: the decode loop
     * answered the abort, or its wait at the decoder gate was interrupted) has
     * heard real audio, and the next check of the same song — the quality
     * comparison asks for the same opening — can use it instead of decoding the
     * same seconds again and queueing for the whole track behind the
     * one-at-a-time gate. A read that failed at a named stage is not audio the
     * song was proved to have.
     */
    internal fun keepAbandonedOpening(bytes: Int, minimumBytes: Int, failedStage: String?): Boolean =
        failedStage == null && bytes >= minimumBytes

    /**
     * The opening a caller may be handed: the whole window, or what an abandoned
     * read left when the caller said that will do.
     *
     * Kept apart because a window that stopped at 12 s is not ninety seconds. The
     * quality comparison can work from what is there, and says so; the song
     * check's full pass and the refine's listen must decode the rest, or they
     * would pay for a transcript of the same 12 s and call it the whole opening.
     */
    internal fun <T : Any> pickOpening(full: T?, partial: T?, acceptPartial: Boolean): T? =
        full ?: partial?.takeIf { acceptPartial }

    /**
     * How long a measurement waits for the row's own opening before giving up.
     *
     * A video row plays the video stream, so its audio is written only because
     * something asked for it; the ordinary budget is the 30 s the pass has
     * always had. A late song-version answer is the authoritative pairing and
     * gets [LATE_MEASUREMENT_OPENING_WAIT_MS]: the 11-05 smoke (41957291428)
     * had the MV's own opening land 62 s after that answer, 32 s past the old
     * wait, and all three MV rows then measured nothing.
     */
    internal fun measurementOpeningWaitMs(lateVersion: Boolean): Long =
        if (lateVersion) LATE_MEASUREMENT_OPENING_WAIT_MS else MEASUREMENT_OPENING_WAIT_MS

    /** The wait a measurement gives a row's own opening. */
    internal const val MEASUREMENT_OPENING_WAIT_MS = 30_000L

    /** The same wait when the row's song version arrived after its first sound. */
    internal const val LATE_MEASUREMENT_OPENING_WAIT_MS = 90_000L

    /** Bytes of [copy] to the end of the [untilSeconds] window, or null when its size or the song's is unknown. */
    private fun openingBytes(
        copy: AudioCache.Rendition,
        untilSeconds: Double,
        durationMs: Long,
    ): Long? {
        if (copy.contentLength <= 0L || durationMs <= 0L) return null
        return (copy.contentLength * ((untilSeconds + 1.0) * 1000.0 / durationMs))
            .toLong().coerceAtMost(copy.contentLength)
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
        /** An abandoned read's shorter opening will do — see [pickOpening]. */
        acceptPartial: Boolean = false,
        onRefusal: ((SongWavRefusal) -> Unit)? = null,
    ): ByteArray? = songWavCopy(context, song, untilSeconds, acceptPartial, onRefusal)?.bytes

    /** [songWav] with the copy it actually opened, so the transcript is tagged with that clock. */
    suspend fun songWavCopy(
        context: Context,
        song: Song,
        untilSeconds: Double = SONG_CHECK_SECONDS,
        acceptPartial: Boolean = false,
        onRefusal: ((SongWavRefusal) -> Unit)? = null,
    ): SongAudio? = withContext(Dispatchers.IO) {
        if (!untilSeconds.isFinite() || untilSeconds <= 0.0) return@withContext null
        val until = untilSeconds.coerceAtMost(MAX_SECONDS)
        val job = coroutineContext[Job]
        val key = songCacheKey(song, until)
        fun inHand(): SongAudio? = pickOpening(recent.get(songKey(key)), recent.get(partialKey(key)), acceptPartial)
        inHand()?.let { return@withContext it }
        // Runs with the screen off too (11-01 user decision): this opening is
        // what the song check and the quality-upgrade comparison listen to.
        // Skipping it in a pocket let a wrong song play unchecked, and the
        // listener then spent more battery on the screen fixing it by hand.
        // It is one bounded opening per track, not the whole video.
        val opened = openSongSource(context, song, until, onRefusal) ?: return@withContext null
        try {
            songLocks.getOrPut(key) { Mutex() }.withLock {
                inHand()?.let {
                    runCatching { opened.data.close() }
                    return@withLock it
                }
                val bytes = AudioDecoder.interruptibleDecode {
                    decodeSong(song, opened, until, { job?.isActive != false }, onRefusal)
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

    /**
     * The opening of a streaming [song] exactly as the cache holds it: the
     * container's own bytes, cut at whatever has arrived, with no decode at all.
     *
     * A speech server reads a cut WebM or fragmented MP4 to its last whole frame
     * (11-06, 4 songs: the same word times as the decoded WAV, median 0 ms, p90
     * 40-80 ms, at 40 % of the upload). So a check no longer waits at
     * [AudioDecoder]'s one-at-a-time gate behind automix, the quality check or
     * the song before: 11-06's emulators asked a median of 98 s after the first
     * sound, and the request itself took 8 s.
     *
     * Waits on cache events for up to [untilSeconds], or settles for [minSeconds]
     * after [QUICK_WAIT_MS]. Null means nothing fit to send, not an error: the
     * caller decodes instead. A file on the device is left to the decoded path.
     */
    internal suspend fun songStreamOpening(
        song: Song,
        untilSeconds: Double,
        minSeconds: Double = untilSeconds,
    ): SongAudio? = withContext(Dispatchers.IO) {
        if (!untilSeconds.isFinite() || untilSeconds <= 0.0) return@withContext null
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (local != null && LocalAudioSource.isLocal(local)) return@withContext null
        val videoId = song.videoId
        val durationMs = song.durationMillis()
        AudioCache.keepForTiming(videoId)
        fun bytesFor(seconds: Double, copy: AudioCache.Rendition): Long =
            openingBytes(copy, seconds, durationMs) ?: (seconds * RAW_BYTES_PER_SECOND).toLong()
        // Only the recording that plays: early on, a quality candidate's head can
        // hold more bytes than the row's own stream, and its words would be for
        // another file (11-06 rlYOsUfpi3M: a first answer on an unknown clock).
        fun ownCopy(): AudioCache.Rendition? = VersionAudioAligner.renditionToMeasure(videoId)?.takeUnless {
            AutomixAnalysisSource.isSubstituteRendition(videoId, it.key) && !StreamChoice.isSubstitute(videoId) &&
                !VersionAudioAligner.isYouTubeUnderAltKey(it.key, StreamChoice.isYouTube(videoId))
        }
        if ((ownCopy()?.cachedPrefix ?: 0L) < AudioCache.MIN_ALIGNMENT_PREFIX_BYTES) {
            // A row that has not started the stream yet: this is what asks for it.
            AudioCache.awaitAnalysisHead(Uri.parse(AutomixAnalysisSource.opusUri(videoId)), HEAD_TIMEOUT_MS)
            // A slow start is not "no audio": giving up here left the song with
            // no check for its whole playback once the sound did come (11-06
            // qsqvJpOgrWw, first sound at 76 s). Ended by the wait or by the sound.
            if ((ownCopy()?.cachedPrefix ?: 0L) < MIN_RAW_BYTES) {
                AudioCache.awaitRenditionPrefix(videoId, MIN_RAW_BYTES, NO_SOUND_WAIT_MS)
            }
        }
        var copy = ownCopy() ?: return@withContext null
        val want = bytesFor(untilSeconds, copy)
        if (!copy.isComplete && copy.cachedPrefix < want &&
            !AudioCache.awaitRenditionPrefix(videoId, want, QUICK_WAIT_MS)
        ) {
            copy = ownCopy() ?: return@withContext null
            val least = bytesFor(minSeconds, copy)
            if (!copy.isComplete && copy.cachedPrefix < least &&
                !AudioCache.awaitRenditionPrefix(videoId, least, (minSeconds * 1000).toLong() + QUICK_WAIT_MS)
            ) {
                TrackLog.d(TAG, "song check: ${copy.cachedPrefix} bytes on disk, $least wanted", videoId)
            }
        }
        copy = ownCopy() ?: return@withContext null
        // Never past what has landed: reading on would wait for the stream. A
        // shorter first look still sends the song check's window when it is there:
        // that request follows anyway, and this way it reads the kept words
        // (11-06: a 40 s and a 90 s request went out together on most songs).
        val take = minOf(copy.cachedPrefix, bytesFor(maxOf(untilSeconds, SONG_CHECK_SECONDS), copy))
        if (take < MIN_RAW_BYTES || take > MAX_RAW_BYTES) return@withContext null
        val source = AudioCache.renditionDataSource(Uri.parse("bitchord://watch?v=$videoId"), copy)
        val bytes = try {
            readBytes(source, take.toInt())
        } catch (e: Exception) {
            TrackLog.w(TAG, "song check: could not read the cached stream: ${e.message}", videoId)
            return@withContext null
        } finally {
            runCatching { source.close() }
        }
        val mime = containerMime(bytes) ?: run {
            TrackLog.d(TAG, "song check: cached stream is no container to send as it is", videoId)
            return@withContext null
        }
        SongAudio(
            bytes,
            copy.key,
            fromFile = false,
            mime = mime,
            whole = copy.isComplete && bytes.size.toLong() >= copy.contentLength,
            roughSeconds = RefineExtendPolicy.cachedSeconds(bytes.size.toLong(), copy.contentLength, durationMs / 1000.0)
                .takeIf { it >= 0.0 } ?: (bytes.size / RAW_BYTES_PER_SECOND),
        )
    }

    private fun readBytes(source: MediaDataSource, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var at = 0
        while (at < size) {
            val read = source.readAt(at.toLong(), bytes, at, size - at)
            if (read <= 0) break
            at += read
        }
        return if (at == size) bytes else bytes.copyOf(at)
    }

    /** The media type a speech server should read [head] as, by its magic bytes; null when it is none of them. */
    internal fun containerMime(head: ByteArray): String? {
        fun at(index: Int, vararg expected: Int): Boolean =
            head.size >= index + expected.size &&
                expected.indices.all { head[index + it].toInt() and 0xFF == expected[it] }
        return when {
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "audio/webm"
            at(4, 0x66, 0x74, 0x79, 0x70) -> "audio/mp4"
            at(0, 0x66, 0x4C, 0x61, 0x43) -> "audio/flac"
            at(0, 0x4F, 0x67, 0x67, 0x53) -> "audio/ogg"
            at(0, 0x49, 0x44, 0x33) -> "audio/mpeg"
            else -> null
        }
    }

    /** How long a check waits for its whole window before settling for what has landed. */
    private const val QUICK_WAIT_MS = 10_000L

    /** How long a check waits for a stream that has not begun at all. */
    private const val NO_SOUND_WAIT_MS = 120_000L

    /** Bytes per second when a stream's length or duration is unknown: 160 kbps, over any audio stream YouTube serves. */
    private const val RAW_BYTES_PER_SECOND = 20_000.0

    /** Under about ten seconds of audio there is nothing to fit. */
    private const val MIN_RAW_BYTES = 150L * 1024

    /** A substitute's lossless file is decoded down rather than uploaded whole. */
    private const val MAX_RAW_BYTES = 16L * 1024 * 1024

    private class SongSource(
        val data: MediaDataSource,
        val fromFile: Boolean,
        val renditionKey: String?,
        val localSourceConfigId: String? = null,
        val localTrackId: String? = null,
    )

    private suspend fun openSongSource(
        context: Context,
        song: Song,
        untilSeconds: Double,
        onRefusal: ((SongWavRefusal) -> Unit)? = null,
    ): SongSource? {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val fromFile = local != null && LocalAudioSource.isLocal(local)
        if (!fromFile) {
            // The opening is all a song check reads, so wait for that rather
            // than for the whole track the way the video WAV has to.
            val opus = Uri.parse(AutomixAnalysisSource.opusUri(song.videoId))
            AudioCache.keepForTiming(song.videoId)
            // A playing copy that already holds the opening needs no head
            // wait: asking anyway spent the head's whole timeout on a check
            // that could answer from bytes already on disk.
            if (!hasOpeningOnDisk(
                    VersionAudioAligner.renditionToMeasure(song.videoId),
                    untilSeconds,
                    song.durationMillis(),
                ) &&
                !AudioCache.awaitAnalysisHead(opus, HEAD_TIMEOUT_MS)
            ) {
                // A blocked video, or one already swapped to its quality copy,
                // never gets its YouTube opening: listen to the copy that plays
                // instead. Its key tags the words with that copy's clock.
                val substitute = VersionAudioAligner.renditionToMeasure(song.videoId)
                if (substitute == null ||
                    substitute.cachedPrefix < AudioCache.MIN_ALIGNMENT_PREFIX_BYTES
                ) {
                    TrackLog.d(TAG, "song check: the opening is not on disk yet", song.videoId)
                    onRefusal?.invoke(SongWavRefusal.NO_WAV)
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
                onRefusal?.invoke(SongWavRefusal.NO_WAV)
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
            if (first.isComplete) return@let
            val need = openingBytes(first, untilSeconds, song.durationMillis()) ?: return@let
            if (first.cachedPrefix < need &&
                !AudioCache.awaitRenditionPrefix(song.videoId, need, OPENING_WAIT_MS)
            ) {
                TrackLog.d(TAG, "song check: opening still downloading; decoding as it comes", song.videoId)
            }
        }
        // Pick once and open that exact copy: asking again after the listen
        // could name a different rendition than the one whose words were heard.
        val copy = VersionAudioAligner.renditionToMeasure(song.videoId) ?: run {
            onRefusal?.invoke(SongWavRefusal.NO_WAV)
            return null
        }
        val data = AudioCache.renditionDataSource(
            Uri.parse("bitchord://watch?v=${song.videoId}"),
            copy,
        )
        if (data == null) {
            TrackLog.d(TAG, "song check: no audio to listen to", song.videoId)
            onRefusal?.invoke(SongWavRefusal.NO_WAV)
            return null
        }
        return SongSource(data, false, copy.key)
    }

    private fun songCacheKey(song: Song, untilSeconds: Double): String =
        "${song.videoId}\u0000${song.localUri.orEmpty()}\u0000$untilSeconds"

    /**
     * How many seconds of [song]'s sound are on disk right now: a downloaded
     * file is all there, a stream only as far as the copy the player has
     * cached. Infinite when the whole file is on disk but its length is not
     * stated.
     */
    internal fun secondsOnDisk(song: Song): Double {
        val local = song.localUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (local != null && LocalAudioSource.isLocal(local)) return wholeSongSeconds(song)
        val copy = VersionAudioAligner.renditionToMeasure(song.videoId) ?: return 0.0
        if (copy.isComplete) return wholeSongSeconds(song)
        val cached = RefineExtendPolicy.cachedSeconds(
            copy.cachedPrefix,
            copy.contentLength,
            song.durationMillis() / 1000.0,
        )
        // A row with no stated length still has the bytes it has.
        return if (cached < 0.0) copy.cachedPrefix / RAW_BYTES_PER_SECOND else cached
    }

    private fun wholeSongSeconds(song: Song): Double =
        (song.durationMillis() / 1000.0).takeIf { it > 0.0 } ?: Double.POSITIVE_INFINITY

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
        opened: SongSource,
        untilSeconds: Double,
        /** False once the caller has gone; see [decode]. */
        wanted: () -> Boolean,
        onRefusal: ((SongWavRefusal) -> Unit)? = null,
    ): ByteArray? {
        // One second past the window, then cut at the sample: a decoded chunk
        // that starts inside the window may run past its end. Not stopped by
        // the screen going off; see [songWav].
        val resampler = Resampler(minOf(RATE * 2 * untilSeconds.toInt(), RATE * 2 * 120))
        val stoppedEarly = try {
            AudioDecoder.decodeMonoStream(
                opened.data,
                0.0,
                untilSeconds + 1,
                abort = { !wanted() },
                label = "video-wav",
            ) { samples, sampleRate, _ ->
                if (!resampler.push(samples, sampleRate)) return@decodeMonoStream
            } == null
        } catch (e: Exception) {
            TrackLog.w(TAG, "song check: could not read the audio: ${e.message}", song.videoId)
            onRefusal?.invoke(SongWavRefusal.DECODE)
            return null
        } finally {
            runCatching { opened.data.close() }
        }
        val pcm = resampler.bytes()
        val minimum = RATE * 2 * 10
        val stoppedAt = if (stoppedEarly) AudioDecoder.takeLastFailure() else null
        if (pcm.size < minimum) {
            TrackLog.d(TAG, "song check: under ten seconds of audio", song.videoId)
            // Nothing heard because the read never got the decoder is not a
            // recording too short to compare (11-05 auto8b: 24 s waited at the
            // gate, zero audio, reported as a cancelled reference).
            onRefusal?.invoke(
                if (stoppedAt == AudioDecoder.FAILURE_GATE) {
                    SongWavRefusal.GATE_TIMEOUT
                } else {
                    SongWavRefusal.TOO_SHORT
                },
            )
            return null
        }
        if (!keepAbandonedOpening(pcm.size, minimum, stoppedAt)) {
            TrackLog.d(TAG, "song check: the read failed; the opening is not kept", song.videoId)
            onRefusal?.invoke(SongWavRefusal.DECODE)
            return null
        }
        val limit = (RATE * 2 * untilSeconds).toInt().let { it - it % 2 }
        val data = if (pcm.size > limit) pcm.copyOf(limit) else pcm
        val wav = header(data.size) + data
        // The opening a read already heard is kept for the next check of this
        // song instead of being thrown away with the abandoned call. Throwing
        // it away made the quality comparison decode the same ninety seconds
        // again, queue at the one-at-a-time gate behind the check it had just
        // duplicated, and be cancelled with the play as "no reference" (11-05
        // smoke r2-b: 35 s decoded, 59 s at the gate, cancelled). Filed apart
        // from the whole window — see [pickOpening] — and not returned: the
        // caller has gone, and the caller's own put would file it as whole.
        TrackLog.d(
            TAG,
            "song check: ${if (opened.fromFile) "file" else "stream"} " +
                "${data.size / (RATE * 2)}s ${data.size / 1024}KB" +
                if (stoppedEarly) " (read abandoned)" else "",
            song.videoId,
        )
        if (stoppedEarly) {
            recent.put(
                partialKey(songCacheKey(song, untilSeconds)),
                SongAudio(
                    wav,
                    opened.renditionKey,
                    opened.fromFile,
                    opened.localSourceConfigId,
                    opened.localTrackId,
                ),
            )
            return null
        }
        return wav
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

    /** Drops everything, for the caches whose whole session is invalidated at once. */
    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}
