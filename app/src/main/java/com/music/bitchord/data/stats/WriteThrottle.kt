package com.music.bitchord.data.stats

/**
 * A minimum interval between writes.
 *
 * Listening is sampled every few seconds, but the month file is up to six
 * hundred tracks: rewriting it at the sample rate is flash wear and battery
 * for numbers nobody reads at that resolution. A forced write (pause, track
 * end, service stop) still goes through — this only thins out the routine
 * ones.
 *
 * Pure and clock-agnostic so the decision can be tested without a device.
 * Synchronised because sampling and the stop path can arrive on different
 * threads.
 */
internal class WriteThrottle(private val intervalMs: Long) {

    private var lastWriteMs: Long? = null

    fun shouldWrite(nowMs: Long): Boolean {
        val last = synchronized(this) { lastWriteMs } ?: return true
        return nowMs - last >= intervalMs
    }

    fun wrote(nowMs: Long) {
        synchronized(this) { lastWriteMs = nowMs }
    }
}
