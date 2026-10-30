package com.music.bitchord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The warm/cold marker is read off disk, but its arithmetic is pure. */
class ProcessRestartWarmthTest {

    private val window = 10 * 60 * 1000L

    @Test
    fun `a missing or zero marker is a cold start`() {
        assertFalse(isWarmProcessRestart(nowMs = 1_000_000L, previousAtMs = 0L, windowMs = window))
    }

    @Test
    fun `a marker inside the window is a warm restart`() {
        assertTrue(isWarmProcessRestart(nowMs = 1_000_000L, previousAtMs = 999_999L, windowMs = window))
        assertTrue(
            isWarmProcessRestart(
                nowMs = 1_000_000L,
                previousAtMs = 1_000_000L - window,
                windowMs = window,
            ),
        )
    }

    @Test
    fun `a marker older than the window is a cold start`() {
        assertFalse(
            isWarmProcessRestart(
                nowMs = 1_000_000L,
                previousAtMs = 1_000_000L - window - 1L,
                windowMs = window,
            ),
        )
    }

    @Test
    fun `a marker from the future is not trusted`() {
        assertFalse(isWarmProcessRestart(nowMs = 1_000_000L, previousAtMs = 1_000_001L, windowMs = window))
    }
}
