package com.music.bitchord.playback

/**
 * Whether a pause saved before the process died is still recent enough for
 * headphones connecting to resume it.
 *
 * The saved value is on `SystemClock.elapsedRealtime`, which keeps counting
 * across a process restart but starts again from zero after a reboot. A value
 * from a previous boot is therefore larger than "now" and must not be treated
 * as a fresh pause.
 */
internal object HeadphoneResumePolicy {

    const val WINDOW_MS = 30L * 60L * 1000L

    fun restoredPauseAt(
        savedElapsedRealtimeMs: Long?,
        nowElapsedRealtimeMs: Long,
        windowMs: Long = WINDOW_MS,
    ): Long? {
        val saved = savedElapsedRealtimeMs ?: return null
        if (saved <= 0L) return null
        if (saved > nowElapsedRealtimeMs) return null
        if (nowElapsedRealtimeMs - saved > windowMs) return null
        return saved
    }
}
