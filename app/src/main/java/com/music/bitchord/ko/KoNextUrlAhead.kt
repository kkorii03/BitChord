package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * UPG-speed item 2: under Direct Source Stream, the next song's YouTube URL is
 * resolved once the current song has played on its own for about ten seconds.
 *
 * The gate holds the next track's every open until 25 s before the transition,
 * so a skip to it before then paid a full client walk (4-10 s of silence on
 * the 11-18 log: I Like It, Call Me Maybe). This resolves only the URL:
 * [StreamResolver.resolve] with its own probe, refusal verdicts and 20-minute
 * TTL, and no addon call and no stream download. When the song starts, the
 * same resolve answers from that cache (or joins the walk still running), so
 * an addon answer that comes first still wins exactly as before.
 *
 * Driven by the playing sampler's existing five-second tick in
 * PlaybackService.driveDirectWindow: no timer of its own, nothing while paused.
 * A song skipped or scrubbed before ten seconds of its own playback never
 * resolves anything ahead; a seek starts the count again.
 */
object KoNextUrlAhead {
    /** Unbroken playback of the current song before the next one's URL is resolved. */
    const val PLAYED_MS = 10_000L

    /** The sampler ticks every 5 s; a tick lands a little either side of the mark. */
    private const val TICK_SLACK_MS = 1_000L

    private data class Anchor(val mediaId: String, val positionMs: Long, val seekAt: Long)

    private var anchor: Anchor? = null
    private var fired: Pair<String, String>? = null
    private var target: String? = null
    private var job: Job? = null

    /** What resolves a video id; a test swaps it. */
    internal var resolve: suspend (String) -> Unit = { StreamResolver.resolve(it) }

    /**
     * Whether this tick is the one to resolve [nextVideo]: the current song has
     * run [PLAYED_MS] since it started or was last seeked, and this pair has
     * not been resolved ahead before. Each new song, each seek and each jump
     * back starts the count from where the playhead is.
     */
    @Synchronized
    internal fun due(currentId: String, positionMs: Long, seekAt: Long, nextId: String, nextVideo: String?): Boolean {
        val held = anchor
        if (held == null || held.mediaId != currentId || held.seekAt != seekAt || positionMs < held.positionMs) {
            anchor = Anchor(currentId, positionMs, seekAt)
            return false
        }
        if (nextVideo.isNullOrBlank()) return false
        if (positionMs - held.positionMs < PLAYED_MS - TICK_SLACK_MS) return false
        val pair = currentId to nextId
        if (fired == pair) return false
        fired = pair
        return true
    }

    /** One playing tick, gate active, with [nextId] next (its stream id [nextVideo], null for a local or addon row). */
    fun onTick(scope: CoroutineScope, currentId: String, positionMs: Long, seekAt: Long, nextId: String, nextVideo: String?) {
        if (!due(currentId, positionMs, seekAt, nextId, nextVideo)) return
        val video = nextVideo ?: return
        TrackLog.d(TAG, "next-url ahead: resolving $video after ${PLAYED_MS / 1000}s of $currentId", about = nextId)
        synchronized(this) {
            job?.cancel()
            target = nextId
            job = scope.launch(Dispatchers.IO + TrackLog.about(nextId)) {
                val startedAt = System.nanoTime()
                val outcome = try {
                    resolve(video)
                    "ready"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "failed (${e.javaClass.simpleName}); the start resolves it as before"
                }
                TrackLog.d(TAG, "next-url ahead: $video $outcome in ${(System.nanoTime() - startedAt) / 1_000_000}ms", about = nextId)
            }
        }
    }

    /**
     * DirectSourceGate's drop: the resolve ahead is for the song that is next
     * or now playing; once it is neither, its wait stops. A walk already sent
     * is shared with any other caller in StreamResolver and is left to finish.
     */
    @Synchronized
    fun keepOnly(admitted: Set<String>) {
        val next = target ?: return
        if (next in admitted) return
        job?.cancel()
        job = null
        target = null
        fired = null // still next on a later tick: asked again, from the cache
        TrackLog.d(TAG, "next-url ahead: dropped $next, no longer next", about = next)
    }

    /** Test hook: forget everything. */
    @Synchronized
    internal fun reset() {
        job?.cancel()
        job = null
        target = null
        anchor = null
        fired = null
    }

    private const val TAG = "BitChord"
}
