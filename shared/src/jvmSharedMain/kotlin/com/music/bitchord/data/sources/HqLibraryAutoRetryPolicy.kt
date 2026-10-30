package com.music.bitchord.data.sources

import com.music.bitchord.data.net.AutomaticRetryPolicy

/**
 * Whether the automatic high-quality pass may work on one target now.
 *
 * Three things park a target without asking the network:
 *
 *  - the app has no window, so Android would refuse the download service that
 *    the pass needs to fetch anything;
 *  - a complete lossless copy is already on disk, so there is nothing worth
 *    fetching for it;
 *  - its automatic retries are exhausted, and only a pass the listener started
 *    may try again.
 *
 * Kept as a pure function so the three rules can be exercised without a
 * device, a player or a download queue.
 */
object HqLibraryAutoRetryPolicy {

    fun shouldAttempt(
        manual: Boolean,
        foreground: Boolean,
        hasCompleteLosslessCopy: Boolean,
        attempts: Int,
        lastFailureMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!foreground) return false
        if (manual) return true
        if (hasCompleteLosslessCopy) return false
        return AutomaticRetryPolicy.mayRetry(
            attempts = attempts,
            lastFailureMs = lastFailureMs,
            nowMs = nowMs,
            manual = false,
        )
    }
}
