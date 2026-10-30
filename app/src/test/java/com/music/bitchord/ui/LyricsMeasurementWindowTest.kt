package com.music.bitchord.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyric re-measure loop must not start another measurement after the
 * wall-clock window or the attempt cap, including after a wait for the screen.
 */
class LyricsMeasurementWindowTest {

    private val giveUpAt = 1_000_000L

    @Test
    fun `a measurement is allowed inside the window and under the cap`() {
        assertTrue(
            lyricsMeasurementAllowed(
                attempts = 0,
                maxAttempts = 10,
                nowMs = giveUpAt - 1,
                giveUpAt = giveUpAt,
                measured = false,
            ),
        )
    }

    @Test
    fun `a screen that comes back after the window starts no measurement`() {
        assertFalse(
            lyricsMeasurementAllowed(
                attempts = 0,
                maxAttempts = 10,
                nowMs = giveUpAt,
                giveUpAt = giveUpAt,
                measured = false,
            ),
        )
    }

    @Test
    fun `the attempt cap and an existing measurement still stop the loop`() {
        assertFalse(
            lyricsMeasurementAllowed(
                attempts = 10,
                maxAttempts = 10,
                nowMs = giveUpAt - 1,
                giveUpAt = giveUpAt,
                measured = false,
            ),
        )
        assertFalse(
            lyricsMeasurementAllowed(
                attempts = 0,
                maxAttempts = 10,
                nowMs = giveUpAt - 1,
                giveUpAt = giveUpAt,
                measured = true,
            ),
        )
    }
}
