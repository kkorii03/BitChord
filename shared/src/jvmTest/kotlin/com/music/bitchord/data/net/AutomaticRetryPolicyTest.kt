package com.music.bitchord.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deterministic failure must not be retried on every app start and playback.
 * One failure rests the target for the cooldown; after the attempt budget it
 * stays parked until the listener asks for it again.
 */
class AutomaticRetryPolicyTest {

    private val now = 1_000_000_000L

    @Test
    fun `a target that never failed may run`() {
        assertTrue(AutomaticRetryPolicy.mayRetry(attempts = 0, lastFailureMs = 0L, nowMs = now, manual = false))
    }

    @Test
    fun `a failure rests the target for the cooldown`() {
        assertFalse(
            AutomaticRetryPolicy.mayRetry(
                attempts = 1,
                lastFailureMs = now - 1_000L,
                nowMs = now,
                manual = false,
            ),
        )
        assertTrue(
            AutomaticRetryPolicy.mayRetry(
                attempts = 1,
                lastFailureMs = now - AutomaticRetryPolicy.COOLDOWN_MS,
                nowMs = now,
                manual = false,
            ),
        )
    }

    @Test
    fun `a target that exhausted its automatic attempts stays parked`() {
        assertFalse(
            AutomaticRetryPolicy.mayRetry(
                attempts = AutomaticRetryPolicy.MAX_FAILURES,
                lastFailureMs = now - AutomaticRetryPolicy.COOLDOWN_MS * 2,
                nowMs = now,
                manual = false,
            ),
        )
    }

    @Test
    fun `a manual retry ignores the cooldown and the attempt budget`() {
        assertTrue(
            AutomaticRetryPolicy.mayRetry(
                attempts = AutomaticRetryPolicy.MAX_FAILURES,
                lastFailureMs = now,
                nowMs = now,
                manual = true,
            ),
        )
    }
}
