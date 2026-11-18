package com.music.bitchord.ko

import android.net.Uri
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.HqHostTracker
import com.music.bitchord.playback.HqSwapPolicy
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.playback.smart.LyricsTiming
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [KO] cold2: the row the listener is waiting on, with YouTube's own copy of it
 * already on disk, plays that copy now under Direct Source Stream.
 *
 * Phone 11-18 17:11 (fqqcXP1D0Fw, first track of a fresh process): 192 s of the
 * row's `#alt` entry (origin YouTube, 3.1 of 3.67 MB) was cached, yet the first
 * note came 6.8 s after the play tap. [com.music.bitchord.playback.AudioCache]'s
 * Direct settle raced the addon against YouTube's walk on the loader thread
 * before the cache was read, and the walk (VISIONOS x3, WEB_EMBEDDED, then
 * WEB_CREATOR) took 5.5 s, only to pin a URL the cached bytes did not need.
 *
 * The race's own rule is "whichever can serve a note first wins; a losing addon
 * goes to the second look" ([DirectSourceGate.firstAnswer]). Cached YouTube
 * bytes can serve a note at once, so they win: the open reads the cache, and
 * the addon lookup and the walk run behind it. When the walk lands, YouTube is
 * pinned (the entry stays YouTube's to finish, as [StreamChoice] requires) and
 * the lookup goes to the second look ([DirectSourceGate.startedOnYouTube]),
 * exactly as when YouTube wins the race today. The YouTube entry is never
 * dropped under its reader, so no addon bytes can continue a YouTube file.
 *
 * Only for a row whose lyric clock is YouTube's (the bytes played are YouTube's),
 * not for a row with a proved addon answer already in hand (that start is
 * instant and plays the better copy), and only with enough cached bytes ahead of
 * the read that the walk lands long before they run out. A row merely prepared
 * ahead keeps the old wait: it has the time.
 */
internal object KoCachedPrefixStart {

    private const val TAG = "BitChord"

    /**
     * Cached bytes needed ahead of the read: 512 KiB is about 26 s of YouTube's
     * 160 kbps Opus (16 s at 256 kbps), against a cold walk measured at 5.5 s.
     */
    const val MIN_AHEAD_BYTES = 512L * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Rows started from their cached copy whose settle has not pinned yet, or
     * whose walk failed. Every reopen of such a row (a seek, the continuation)
     * skips the Direct settle, so the addon branch can never drop the entry
     * and continue a YouTube file mid-play; it reads the cache or reaches the
     * resolver's YouTube branch. Cleared when the row stops being audible.
     */
    private val started = ConcurrentHashMap.newKeySet<String>()

    /**
     * [KO] fix1119 F1: rows moved off their cached copy onto the addon's as a
     * rendition of their own ([koOnCachedPrefixSettled]). A plain-key open of
     * such a row (the old period's loader, released a moment too late) is
     * refused rather than served the addon into YouTube's entry ([refusesSplice]).
     */
    private val rescued = ConcurrentHashMap.newKeySet<String>()

    /** What a settle behind the cached copy leaves to the player. */
    sealed interface Settled {
        val videoId: String

        /**
         * The row started before its second look could be marked, so the
         * look that ran at the first sound found nothing pending: ask again.
         */
        data class LookAgain(override val videoId: String) : Settled

        /**
         * YouTube's walk failed under a partial copy and the addon answered:
         * move onto [stream] as a new rendition before the cached bytes run
         * out, instead of failing mid-song when they do.
         */
        data class Rescue(override val videoId: String, val stream: SourceStream) : Settled
    }

    private val _settled = MutableSharedFlow<Settled>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Collected by [PlaybackService] ([koOnCachedPrefixSettled]). */
    val settled: SharedFlow<Settled> = _settled.asSharedFlow()

    /**
     * Pure gate, see the class doc. [youTubeGone]: YouTube already said this
     * video is unplayable, so the walk behind the copy can only fail; the old
     * settle (which plays the addon from the start) runs instead.
     */
    internal fun eligible(
        playing: Boolean,
        youTubeClock: Boolean,
        addonReady: Boolean,
        cachedAheadBytes: Long,
        complete: Boolean,
        youTubeGone: Boolean = false,
    ): Boolean = playing && youTubeClock && !addonReady && !youTubeGone &&
        (complete || cachedAheadBytes >= MIN_AHEAD_BYTES)

    /**
     * Called by the Direct settle once the entry is known to hold YouTube's
     * bytes. True: the open goes straight to the cache (and the settle runs
     * behind it). False: the old settle runs.
     */
    fun takesOver(
        videoId: String,
        target: TrackMatcher.Target,
        cachedAheadBytes: Long,
        complete: Boolean,
    ): Boolean {
        if (videoId in started) return true
        val ok = eligible(
            playing = DirectSourceGate.isPlaying(videoId),
            youTubeClock = LyricsTiming.playingClock(videoId) == LyricsTiming.Clock.YouTube,
            addonReady = KoDirectLead.reusable(videoId)?.isCompleted == true,
            cachedAheadBytes = cachedAheadBytes,
            complete = complete,
            youTubeGone = StreamResolver.isUnplayable(videoId),
        )
        if (!ok || !started.add(videoId)) return ok
        TrackLog.d(
            TAG,
            "cold-prefix: $videoId plays ${if (complete) "its whole" else "${cachedAheadBytes / 1024}KB+ of its"} " +
                "cached YouTube copy now; the addon and the walk settle behind it",
            about = videoId,
        )
        behind(videoId, target, complete)
        return true
    }

    /** Starts [settleBehind] for [videoId]; replaced by tests, which must not reach the network. */
    internal var behind: (String, TrackMatcher.Target, Boolean) -> Unit = ::launchBehind

    private fun launchBehind(videoId: String, target: TrackMatcher.Target, complete: Boolean) {
        val refused = StreamChoice.substitutesRefused(videoId)
        scope.launch(TrackLog.about(videoId)) {
            settleBehind(
                videoId = videoId,
                lookup = { if (refused) null else DirectSourceGate.startLookup(videoId, target) },
                walk = { StreamResolver.resolve(videoId) },
                stillWanted = { DirectSourceGate.isPlaying(videoId) && StreamChoice.of(videoId) == null },
                pin = { url ->
                    StreamChoice.remember(
                        videoId,
                        SourceStream(url, headers = StreamResolver.mediaHeadersFor(url)),
                        substituted = false,
                    )
                },
                handOver = { running -> DirectSourceGate.startedOnYouTube(videoId, target, running) },
                complete = complete,
                onSettled = { _settled.tryEmit(it) },
            )
        }
    }

    /**
     * The settle the open used to wait for, minus the addon branch's drop:
     * the lookup starts beside the walk, the walk's URL is pinned, and the
     * lookup (still running or answered) is handed to the second look, which
     * is asked to look again ([Settled.LookAgain]): its look at the first
     * sound ran before anything was pending. Nothing when the row is no
     * longer the one waiting.
     *
     * [KO] fix1119 F1: a failed walk used to drop the addon's answer, and the
     * continuation past a partial copy then failed in the resolver's YouTube
     * branch mid-song. Now a whole copy still hands the lookup to the second
     * look (it plays to its end without YouTube), and a partial one waits for
     * the addon's answer ([lastResort]) and asks the player to move onto it as
     * its own rendition ([Settled.Rescue]); the entry is never continued by it.
     *
     * @return whether YouTube was pinned.
     */
    internal suspend fun settleBehind(
        videoId: String,
        lookup: () -> Deferred<SourceStream?>?,
        walk: suspend () -> String,
        stillWanted: () -> Boolean,
        pin: (String) -> Unit,
        handOver: (Deferred<SourceStream?>?) -> Unit,
        complete: Boolean = false,
        lastResort: suspend (Deferred<SourceStream?>) -> SourceStream? = { DirectSourceGate.lastResort(videoId, it) },
        onSettled: (Settled) -> Unit = {},
    ): Boolean {
        val running = runCatching { lookup() }.getOrNull()
        val url = try {
            walk()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.d(TAG, "cold-prefix: walk for $videoId failed (${e.message}); the cached copy plays on", about = videoId)
            null
        }
        if (url == null) {
            if (!stillWanted()) return false
            if (complete) {
                handOver(running)
                onSettled(Settled.LookAgain(videoId))
                return false
            }
            val addon = try {
                running?.let { lastResort(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (addon == null || !stillWanted()) {
                TrackLog.d(TAG, "cold-prefix: no addon copy of $videoId either; recovery takes it where the cached copy ends", about = videoId)
                return false
            }
            onSettled(Settled.Rescue(videoId, addon))
            return false
        }
        if (!stillWanted()) return false
        pin(url)
        handOver(running)
        onSettled(Settled.LookAgain(videoId))
        TrackLog.d(TAG, "cold-prefix: $videoId pinned to YouTube behind the cached copy", about = videoId)
        return true
    }

    /** [PlaybackService] moved [videoId] onto the addon's own rendition. */
    fun onRescued(videoId: String) {
        rescued += videoId
    }

    /**
     * Whether the resolver must refuse to serve [videoId]'s pinned stream into
     * this open: a rescued row's pin is the addon, and an open that is not its
     * own rendition ([ownRendition]) would continue YouTube's entry with it.
     */
    fun refusesSplice(videoId: String, ownRendition: Boolean): Boolean =
        !ownRendition && videoId in rescued && StreamChoice.isSubstitute(videoId)

    /** The audible rows changed: rows no longer audible start afresh next time. */
    fun keepOnly(playing: Set<String>) {
        started.retainAll(playing)
        rescued.retainAll(playing)
    }

    internal fun startedForTest(videoId: String): Boolean = videoId in started

    internal fun resetForTest() {
        started.clear()
        rescued.clear()
    }
}

/**
 * [KO] fix1119 F1: carries out what [KoCachedPrefixStart.settleBehind] left.
 * Collected on the main thread from the service's own scope; event-driven.
 */
internal fun PlaybackService.koOnCachedPrefixSettled(event: KoCachedPrefixStart.Settled) {
    val live = player ?: return
    when (event) {
        is KoCachedPrefixStart.Settled.LookAgain -> koRetryBetterCopy(live, event.videoId)
        is KoCachedPrefixStart.Settled.Rescue -> scope.launch(TrackLog.about(event.videoId)) {
            koRescueCachedPrefix(event.videoId, event.stream)
        }
    }
}

/**
 * The row plays YouTube's partial cached copy and YouTube could not be walked:
 * the addon's copy goes in as an upgraded rendition (its own cache entry, the
 * way a rate-limited host is resumed elsewhere), at the same point of the
 * recording, before the cached bytes run out.
 */
private suspend fun PlaybackService.koRescueCachedPrefix(id: String, stream: SourceStream) {
    val service = this
    val verified = withContext(Dispatchers.IO) {
        runCatching { HqVerificationStore.get(service, id, stream.sourceConfigId, stream.trackId) }.getOrNull()
    }
    val live = player ?: return
    val item = live.currentMediaItem
    val uri = item?.localConfiguration?.uri
    val why = when {
        item == null || item.mediaId != id -> "it is no longer the current row"
        uri == null || uri.getQueryParameter("v") != id || QualityUpgrade.cacheTag(uri) != null -> "it plays another rendition"
        StreamChoice.of(id) != null -> "a stream was pinned meanwhile"
        live.playerError != null -> "the player already failed"
        crossfade?.isTransitioning() == true -> "a transition is under way"
        else -> null
    }
    if (why != null || item == null || uri == null) {
        TrackLog.d("BitChord", "cold-prefix: not moving $id onto the addon: $why", about = id)
        return
    }
    val position = live.currentPosition
    val resumeMs = HqSwapPolicy.resumeAtMs(position, null, null, verified?.offsetMs, verified?.rate)
    val upgraded = QualityUpgrade.upgradedUri(uri.toString())
    KoCachedPrefixStart.onRescued(id)
    QualityUpgrade.force(id, stream)
    HqHostTracker.beginDownload(id, HqHostTracker.hostOf(stream.url)) // ended by cacheWholeRendition below
    swappingMediaId = id
    live.replaceMediaItem(
        live.currentMediaItemIndex,
        item.buildUpon().setUri(upgraded).withResolvedStreamType(stream.url).build(),
    )
    live.seekTo(live.currentMediaItemIndex, resumeMs)
    live.prepare()
    StreamChoice.remember(id, stream, substituted = true)
    val name = stream.sourceConfigId?.let { SourceRegistry.config(it)?.displayName } ?: "Source"
    NerdStats.onSourceStream(id, stream.format, name)
    TrackLog.i(
        "BitChord",
        "cold-prefix: $id moved onto $name at ${resumeMs}ms (copy ${position}ms) before the cached copy runs out",
        about = id,
    )
    scope.launch(TrackLog.about(id)) {
        AudioCache.cacheWholeRendition(Uri.parse(upgraded), item.mediaMetadata.title?.toString())
    }
}
