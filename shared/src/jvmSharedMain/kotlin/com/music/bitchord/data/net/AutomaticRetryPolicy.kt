package com.music.bitchord.data.net

/**
 * How often an automatic repair may be retried after it failed.
 *
 * A server refusal already has its own short backoff. This is the other kind
 * of failure: the source no longer exists, always serves a lower rung, or the
 * same wrong recording. Those do not fix themselves between two plays, so an
 * app start or a track transition must not restart them every time. One
 * failure rests the target for [COOLDOWN_MS]; after [MAX_FAILURES] automatic
 * attempts it stays parked until the listener asks for it again.
 */
object AutomaticRetryPolicy {

    /** Automatic attempts allowed before only a manual retry may run it. */
    const val MAX_FAILURES = 2

    /** How long a failed target is left alone before one more automatic try. */
    const val COOLDOWN_MS = 24L * 60L * 60L * 1000L

    fun mayRetry(
        attempts: Int,
        lastFailureMs: Long,
        nowMs: Long,
        manual: Boolean,
    ): Boolean {
        if (manual) return true
        if (attempts <= 0) return true
        if (attempts >= MAX_FAILURES) return false
        return nowMs - lastFailureMs >= COOLDOWN_MS
    }
}
