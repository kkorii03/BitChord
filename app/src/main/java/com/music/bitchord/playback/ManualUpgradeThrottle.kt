package com.music.bitchord.playback

/**
 * App-side gate for explicit quality retries. Automatic upgrades never pass
 * through this object.
 */
internal class ManualUpgradeThrottle(private val cooldownMs: Long) {
    private var lastAcceptedAtMs: Long? = null

    @Synchronized
    fun tryAcquire(nowMs: Long): Long {
        val previous = lastAcceptedAtMs
        if (previous != null) {
            val remaining = cooldownMs - (nowMs - previous)
            if (remaining > 0) return remaining
        }
        lastAcceptedAtMs = nowMs
        return 0L
    }
}
