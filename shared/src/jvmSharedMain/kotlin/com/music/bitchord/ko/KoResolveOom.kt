package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import java.io.IOException

/**
 * [KO] oom3: a stream walk that runs out of memory fails like any other walk.
 *
 * The InnerTubeX cipher solve copies a 3.8M-char player several times (11-19
 * 02:08 and 02:36: OutOfMemoryError in EjsChallengeSolver.solveOnce). The walk
 * is one shared Deferred in StreamResolver.coalescedResolve, so the playing
 * track can join the very walk that hit it, and every rung inside catches only
 * Exception. As an Error it reached each awaiter as-is: a caller in a root
 * launch that catches Exception died with it, and Media3's loader turns an OOM
 * into an UnexpectedLoaderException, which its default policy does not retry.
 * As an IOException it is the ordinary retryable failure: ExoPlayer backs off
 * and asks again, by when the solve's strings are garbage.
 *
 * Only OutOfMemoryError is converted; cancellation and every other throwable
 * pass unchanged.
 */
object KoResolveOom {
    suspend fun <T> failSoft(videoId: String, walk: suspend () -> T): T = try {
        walk()
    } catch (e: OutOfMemoryError) {
        TrackLog.w(TAG, "resolve: $videoId ran out of memory (${e.message}); failed as a retryable lookup")
        throw IOException("stream lookup ran out of memory for $videoId", e)
    }

    private const val TAG = "BitChord"
}
