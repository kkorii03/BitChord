package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.smart.LyricsTiming
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Pure gate, see the class doc. */
    internal fun eligible(
        playing: Boolean,
        youTubeClock: Boolean,
        addonReady: Boolean,
        cachedAheadBytes: Long,
        complete: Boolean,
    ): Boolean = playing && youTubeClock && !addonReady &&
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
        )
        if (!ok || !started.add(videoId)) return ok
        TrackLog.d(
            TAG,
            "cold-prefix: $videoId plays ${if (complete) "its whole" else "${cachedAheadBytes / 1024}KB+ of its"} " +
                "cached YouTube copy now; the addon and the walk settle behind it",
            about = videoId,
        )
        behind(videoId, target)
        return true
    }

    /** Starts [settleBehind] for [videoId]; replaced by tests, which must not reach the network. */
    internal var behind: (String, TrackMatcher.Target) -> Unit = ::launchBehind

    private fun launchBehind(videoId: String, target: TrackMatcher.Target) {
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
            )
        }
    }

    /**
     * The settle the open used to wait for, minus the addon branch's drop:
     * the lookup starts beside the walk, the walk's URL is pinned, and the
     * lookup (still running or answered) is handed to the second look. Nothing
     * when the walk fails or the row is no longer the one waiting.
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
    ): Boolean {
        val running = runCatching { lookup() }.getOrNull()
        val url = try {
            walk()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackLog.d(TAG, "cold-prefix: walk for $videoId failed (${e.message}); the cached copy plays on", about = videoId)
            null
        } ?: return false
        if (!stillWanted()) return false
        pin(url)
        handOver(running)
        TrackLog.d(TAG, "cold-prefix: $videoId pinned to YouTube behind the cached copy", about = videoId)
        return true
    }

    /** The audible rows changed: rows no longer audible start afresh next time. */
    fun keepOnly(playing: Set<String>) {
        started.retainAll(playing)
    }

    internal fun startedForTest(videoId: String): Boolean = videoId in started

    internal fun resetForTest() = started.clear()
}
