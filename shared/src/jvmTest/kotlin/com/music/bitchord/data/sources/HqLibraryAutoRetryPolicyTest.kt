package com.music.bitchord.data.sources

import com.music.bitchord.data.net.AutomaticRetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic high-quality pass must not restart work that cannot finish:
 * a background process has no window for the download service, a copy already
 * at the target rung has nothing left to fetch, and an exhausted target belongs
 * to the manual pass only.
 */
class HqLibraryAutoRetryPolicyTest {

    private val now = 1_000_000L

    private fun shouldAttempt(
        manual: Boolean = false,
        foreground: Boolean = true,
        alreadyAtTargetTier: Boolean = false,
        attempts: Int = 0,
        lastFailureMs: Long = 0L,
    ) = HqLibraryAutoRetryPolicy.shouldAttempt(
        manual = manual,
        foreground = foreground,
        alreadyAtTargetTier = alreadyAtTargetTier,
        attempts = attempts,
        lastFailureMs = lastFailureMs,
        nowMs = now,
    )

    @Test
    fun `a fresh target runs while the app is on screen`() {
        assertTrue(shouldAttempt())
    }

    @Test
    fun `a background process does not start the download service`() {
        assertFalse(shouldAttempt(foreground = false))
        assertFalse(shouldAttempt(foreground = false, manual = true))
    }

    @Test
    fun `a copy already at the target tier is not fetched again`() {
        assertFalse(shouldAttempt(alreadyAtTargetTier = true))
    }

    @Test
    fun `a manual pass may still replace a copy at the target tier`() {
        assertTrue(shouldAttempt(manual = true, alreadyAtTargetTier = true))
    }

    @Test
    fun `an exhausted target waits for a manual pass`() {
        val exhausted = AutomaticRetryPolicy.MAX_FAILURES
        assertFalse(
            shouldAttempt(
                attempts = exhausted,
                lastFailureMs = now - AutomaticRetryPolicy.COOLDOWN_MS * 10,
            ),
        )
        assertTrue(
            shouldAttempt(
                manual = true,
                attempts = exhausted,
                lastFailureMs = now - AutomaticRetryPolicy.COOLDOWN_MS * 10,
            ),
        )
    }

    @Test
    fun `a target inside its cooldown is left alone until the cooldown passes`() {
        assertFalse(
            shouldAttempt(
                attempts = 1,
                lastFailureMs = now - AutomaticRetryPolicy.COOLDOWN_MS + 1,
            ),
        )
        assertTrue(
            shouldAttempt(
                attempts = 1,
                lastFailureMs = now - AutomaticRetryPolicy.COOLDOWN_MS,
            ),
        )
    }
}
