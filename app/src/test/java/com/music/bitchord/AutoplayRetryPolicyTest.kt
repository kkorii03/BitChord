package com.music.bitchord

import com.music.bitchord.playback.ContinuationState
import com.music.bitchord.playback.autoplayNeedsContinuationTerminalRecheck
import com.music.bitchord.playback.autoplaySeedAfterExhaustedRetries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two events that used to leave an enabled AutoPlay queue empty until the
 * next track change: a bounded continuation reaching its terminal state while
 * the current row is already last, and an empty station reply whose retry
 * budget is used up while the seed stays latched.
 */
class AutoplayRetryPolicyTest {

    @Test
    fun `a terminal continuation asks autoplay to look again`() {
        assertTrue(autoplayNeedsContinuationTerminalRecheck(ContinuationState.EXHAUSTED))
        assertTrue(autoplayNeedsContinuationTerminalRecheck(ContinuationState.BLOCKED))
    }

    @Test
    fun `a continuation that can still grow does not trigger a recheck`() {
        assertFalse(autoplayNeedsContinuationTerminalRecheck(ContinuationState.AVAILABLE))
        assertFalse(autoplayNeedsContinuationTerminalRecheck(ContinuationState.IN_FLIGHT))
    }

    @Test
    fun `an exhausted empty reply releases its own seed for a later event`() {
        assertNull(autoplaySeedAfterExhaustedRetries("current", "current", retriesRemaining = 0))
        assertNull(autoplaySeedAfterExhaustedRetries("current", "current", retriesRemaining = -1))
    }

    @Test
    fun `a retry still allowed keeps the seed to deduplicate the same track`() {
        assertEquals(
            "current",
            autoplaySeedAfterExhaustedRetries("current", "current", retriesRemaining = 1),
        )
    }

    @Test
    fun `exhaustion never clears a newer tracks seed`() {
        assertEquals(
            "newer",
            autoplaySeedAfterExhaustedRetries("newer", "current", retriesRemaining = 0),
        )
        assertNull(autoplaySeedAfterExhaustedRetries(null, "current", retriesRemaining = 0))
    }
}
