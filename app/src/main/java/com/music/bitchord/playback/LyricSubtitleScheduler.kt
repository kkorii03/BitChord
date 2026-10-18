package com.music.bitchord.playback

/**
 * When the next lyric-line subtitle is due.
 *
 * The playback service used to wake every 500 ms for the whole song just to
 * compare the current position against the line list. Nothing between two line
 * boundaries can change that answer, so the only wake-up that is needed is the
 * next boundary itself. The arithmetic is kept here, pure, so it can be tested
 * without a player: the delay is the media time to the next line divided by the
 * playback speed (a line arrives sooner at 1.5x than at 0.75x).
 *
 * Only lines strictly after the current position count. A boundary exactly at
 * the position has already been applied (or is being applied now), and asking
 * for it again would schedule a zero-length wake-up that never advances.
 */
internal object LyricSubtitleScheduler {

    /**
     * A floor on the delay so a position that has already reached every line
     * cannot turn the scheduler into a busy loop. It is also the margin that
     * makes the wake-up land after the boundary rather than a millisecond
     * before it, which would leave the subtitle one line behind.
     */
    const val MIN_DELAY_MS = 100L

    /**
     * Milliseconds until [lineStartsMs] first passes [positionMs], or null when
     * there is no later line — the subtitle will not change again.
     *
     * [speed] is the player's playback rate. Non-positive and non-finite values
     * fall back to 1x rather than producing an infinite delay.
     */
    fun delayUntilNextLine(
        lineStartsMs: List<Long>,
        positionMs: Long,
        speed: Float,
    ): Long? {
        val next = lineStartsMs.asSequence().firstOrNull { it > positionMs } ?: return null
        val remainingMs = (next - positionMs).coerceAtLeast(0L)
        val rate = if (speed.isFinite() && speed > 0.01f) speed else 1f
        val wallClockMs = (remainingMs / rate).toLong()
        return wallClockMs.coerceAtLeast(MIN_DELAY_MS)
    }
}
