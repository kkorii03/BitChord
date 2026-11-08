package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.BatteryPolicy
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [KO] hq-ahead
 *
 * Direct Source Stream's lookup for the track that is about to play next also
 * asks the quick sources ranked above YouTube that are not addons — Telegram,
 * JioSaavn — so the next song starts on their copy instead of starting on
 * YouTube and swapping to it 12-28 s in (an audible seam and a lyric-clock
 * change; user log 11-07/08: 4 of 21 automatic transitions).
 *
 * Nothing new is scheduled. The question rides inside
 * [com.music.bitchord.playback.DirectSourceGate]'s existing lookup, which is
 * opened only by its 25 s window for the track that is really next and is
 * waited on for at most its existing 15 s. Its answer reaches the player the
 * way an addon's does: the resolver and the window's read-ahead pin it in
 * [StreamChoice] under their own guards (the YouTube-filled entry is dropped
 * first, a pinned track is never re-pinned), and it plays to the end on its own
 * clock. A lookup that has not answered by then leaves the track to today's
 * path: YouTube first, then the second look once it sounds. The high-quality
 * half also stops the moment the song becomes audible or stops being next, so
 * it never holds a first note past the transition.
 *
 * The bar is the mid-song upgrade's own: the same strict original-recording
 * match and runtime tolerance, the same ninety-second sound comparison against
 * the YouTube opening, and a lossy copy only when it clears the same margin
 * over YouTube's normal Opus. A lossy answer is kept only when every source
 * answered for certain: under Direct Source Stream a substitute that started
 * the song is never looked at again, so a FLAC a timed-out source might still
 * hold must not lose to a 320 kbps copy here. That case falls back instead.
 */
internal object KoDirectHqAhead {

    private const val TAG = "BitChord"

    /** YouTube's ordinary Opus rung, the floor a lossy copy has to clear by [SourceResolver.worthSwapping]'s margin. */
    private val YOUTUBE_NORMAL = StreamFormat(codec = "opus", kbps = 160)

    /**
     * The proof reads about this much of a candidate (19 MB for 16-bit/44.1 kHz
     * FLAC, 63 MB for 24-bit/96 kHz: opus-nexthq) for a song a skip may never
     * reach. 24/96 (63 MB) is still proved: 25 s before its start the next song
     * almost always plays, and skipping the proof only moves the same download to a
     * mid-song swap. A candidate whose head would be bigger than [MAX_HEAD_BYTES] (24/192) is
     * not proved ahead; the song starts on YouTube and the second look, which
     * runs only while it plays, still upgrades it.
     */
    private const val HEAD_SECONDS = 108L
    internal const val MAX_HEAD_BYTES = 70_000_000L

    /** Rough size of the opening the proof would read from [format]; null when the source states too little to tell. */
    internal fun estimatedHeadBytes(format: StreamFormat): Long? {
        val depth = format.bitDepth
        val rate = format.sampleRateHz
        val kbps = format.kbps
        val bytesPerSecond = when {
            format.isLossless == true && depth != null && rate != null -> depth * rate * 2L / 8L
            kbps != null -> kbps * 125L
            else -> return null
        }
        return bytesPerSecond * HEAD_SECONDS
    }

    /** False for a candidate too big to read ahead for a song that may be skipped. */
    internal fun headWithinCap(format: StreamFormat): Boolean =
        (estimatedHeadBytes(format) ?: 0L) <= MAX_HEAD_BYTES

    /** Unit tests only: the sound comparison, which needs an app context in production. */
    @Volatile
    internal var proofForTest: (suspend (SourceStream) -> Boolean)? = null

    /**
     * The next row's id when it streams another upload (`v=` is not its media
     * id — a downloaded song played from its streamed recording). The window's
     * read-ahead looks that row up by its media id, so a copy proved against
     * that id's YouTube audio would be pinned for a recording the player does
     * not read, and the lyrics would take its clock. Such a row is left out.
     */
    @Volatile
    private var streamsElsewhere: String? = null

    /** From the window, before its read-ahead starts: what the next row really streams. */
    fun noteNextStream(mediaId: String, streamedId: String?) {
        streamsElsewhere = mediaId.takeIf { streamedId != null && streamedId != mediaId }
    }

    /**
     * Whether the lookup for a track also asks the high-quality sources.
     *
     * Only for the track the window opened as next, not yet audible: a track
     * that is already playing races YouTube instead (see
     * `DirectSourceGate.firstAnswer`) and gets its upgrade from the second look,
     * unchanged. Never while saving power or on a metered connection: the
     * proof downloads the candidate's opening (a FLAC head is 19-63 MB) for a
     * song a skip may never reach, the same rule read-ahead bytes follow
     * ([BatteryPolicy.cachePrefetchBytesAllowed]). Never without a proof: a copy
     * that was not compared must not start a song.
     */
    fun asksAhead(
        nextNotPlaying: Boolean,
        lowPower: Boolean,
        metered: Boolean,
        trackEligible: Boolean,
        hasProof: Boolean,
        awakeSources: Int,
    ): Boolean = nextNotPlaying &&
        BatteryPolicy.cachePrefetchBytesAllowed(metered = metered, lowPower = lowPower) &&
        trackEligible &&
        hasProof &&
        awakeSources > 0

    /** [asksAhead] with the live settings, stores and source list read. */
    fun wanted(
        videoId: String,
        target: TrackMatcher.Target,
        nextNotPlaying: Boolean,
        hasProof: Boolean,
    ): Boolean {
        if (!nextNotPlaying || videoId == streamsElsewhere) return false
        val eligible = target.title.isNotBlank() && !target.isVideo && target.durationSec != null &&
            SourceRegistry.parseTrackKey(videoId) == null &&
            videoId !in Downloads.saved.value &&
            !OriginalVersion.isPinned(videoId) &&
            !StreamChoice.substitutesRefused(videoId) &&
            videoId !in QualityUpgrade.refused
        return asksAhead(
            nextNotPlaying = true,
            lowPower = AppSettings.lowPower.value,
            metered = AppSettings.meteredConnection.value == true,
            trackEligible = eligible,
            hasProof = hasProof,
            awakeSources = if (eligible) {
                // No target: the signed-out Telegram line is the walk's to log, once.
                sources().count { !SourceResolver.SourceCooldown.isResting(it.configId) }
            } else {
                0
            },
        )
    }

    /** Every enabled source ranked above YouTube that is not an addon, in the listener's order. */
    fun sources(target: TrackMatcher.Target? = null): List<MusicSource> {
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
        if (youtube <= 0) return emptyList()
        return SourceResolver.automaticUpgradeCandidates(active.take(youtube), target)
            .filter { it.kind != SourceKind.ADDON }
    }

    /**
     * The proved high-quality copy of [target] from [sources], or null.
     *
     * All of them are asked at once and each is waited for, lossless first;
     * a proved lossless answer ends the walk.
     */
    suspend fun stream(
        target: TrackMatcher.Target,
        verify: suspend (SourceStream) -> Boolean,
        sources: List<MusicSource> = sources(target),
    ): SourceStream? {
        if (target.title.isBlank() || target.isVideo || target.durationSec == null || sources.isEmpty()) return null
        val report = SourceResolver.SearchReport()
        val found = SourceResolver.bestAcross(
            sources,
            target,
            SourceResolver.requestForNow(),
            waitForAll = true,
            strictLength = true,
            driftSec = SourceResolver.upgradeSlackSec(),
            requireSharedArtist = true,
            strictOriginal = true,
            allowArtistUnverified = true,
            report = report,
            enough = { HqQuality.isLosslessTier(it.format) },
        ) { _, candidate ->
            SourceResolver.worthSwapping(candidate.format, YOUTUBE_NORMAL) &&
                headWithinCap(candidate.format) &&
                verify(candidate)
        } ?: return null
        val (source, stream) = found
        if (!HqQuality.isLosslessTier(stream.format) && !report.allConclusive) {
            TrackLog.d(
                TAG,
                "hq-ahead: '${target.title}' ${stream.format.summary} from ${source.displayName} not started; " +
                    "a source did not answer, so the second look keeps its chance at lossless",
            )
            return null
        }
        return stream
    }

    /**
     * The direct lookup's answer: the addons' when they have one, otherwise the
     * high-quality sources'. Both run at once, so the addons' few seconds cost
     * nothing extra.
     *
     * [aheadOf] cancelled — the song started, or the queue moved and it is no
     * longer next — stops the high-quality half, and it then counts as no answer: whoever
     * waits on the lookup sees null, never a cancellation of its own.
     */
    suspend fun lookup(
        title: String,
        aheadOf: Job?,
        addons: suspend () -> SourceStream?,
        hq: (suspend () -> SourceStream?)?,
    ): SourceStream? = coroutineScope {
        val startedAt = System.nanoTime()
        val ahead = hq?.let { ask ->
            async {
                try {
                    ask()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // Never allowed to take the addons' answer down with it.
                    TrackLog.d(TAG, "hq-ahead: '$title' failed: ${failed.message}")
                    null
                }
            }
        }
        val dropped = if (ahead != null) aheadOf?.invokeOnCompletion { ahead.cancel() } else null
        try {
            val addon = addons()
            if (addon != null) {
                ahead?.cancel()
                return@coroutineScope addon
            }
            if (ahead == null) return@coroutineScope null
            val answer = try {
                ahead.await()
            } catch (cancelled: CancellationException) {
                // The lookup itself still runs: only the high-quality half was
                // stopped, because the track stopped being next.
                currentCoroutineContext().ensureActive()
                TrackLog.d(TAG, "hq-ahead: '$title' dropped; the song started or the queue moved")
                return@coroutineScope null
            }
            val ms = (System.nanoTime() - startedAt) / 1_000_000
            TrackLog.d(
                TAG,
                if (answer != null) {
                    "hq-ahead: '$title' ready at ${answer.format.summary} from " +
                        "${answer.sourceConfigId?.let { SourceRegistry.config(it)?.displayName } ?: "source"} ms=$ms"
                } else {
                    "hq-ahead: '$title' none ms=$ms"
                },
            )
            answer
        } finally {
            dropped?.dispose()
        }
    }
}
