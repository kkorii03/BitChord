package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed playback attempt does next.
 *
 * A definitive refusal is a property of the recording and must not spend the
 * retry budget on the same id; a timeout or a refusal on a URL that may have
 * expired is a property of this minute and still retries.
 */
class UnplayableRecoveryTest {

    @Test
    fun `a definitive refusal with another upload swaps to it`() {
        assertEquals(
            UnplayableRecoveryAction.SWAP_ALTERNATE,
            UnplayableRecovery.decide(definitive = true, hasAlternate = true),
        )
    }

    @Test
    fun `a definitive refusal without another upload skips`() {
        assertEquals(
            UnplayableRecoveryAction.SKIP_NEXT,
            UnplayableRecovery.decide(definitive = true, hasAlternate = false),
        )
    }

    @Test
    fun `a transient failure retries even when an alternate was found`() {
        assertEquals(
            UnplayableRecoveryAction.RETRY,
            UnplayableRecovery.decide(definitive = false, hasAlternate = true),
        )
        assertEquals(
            UnplayableRecoveryAction.RETRY,
            UnplayableRecovery.decide(definitive = false, hasAlternate = false),
        )
    }

    @Test
    fun `an already marked id is definitive regardless of the fresh reason`() {
        assertTrue(UnplayableRecovery.isDefinitive(permanentReason = null, alreadyMarkedUnplayable = true))
        assertTrue(UnplayableRecovery.isDefinitive("Read timed out", alreadyMarkedUnplayable = true))
    }

    @Test
    fun `a takedown is definitive and a timeout is not`() {
        assertTrue(
            UnplayableRecovery.isDefinitive(
                permanentReason = "Got error UNPLAYABLE: blocked due to the claimed content",
                alreadyMarkedUnplayable = false,
            ),
        )
        assertFalse(
            UnplayableRecovery.isDefinitive(
                permanentReason = "Read timed out",
                alreadyMarkedUnplayable = false,
            ),
        )
        assertFalse(UnplayableRecovery.isDefinitive(null, alreadyMarkedUnplayable = false))
    }

    @Test
    fun `a definitive run is bounded so a repeated row cannot loop for ever`() {
        assertTrue(UnplayableRecovery.allowsVerdictSkip(0))
        assertTrue(
            UnplayableRecovery.allowsVerdictSkip(
                UnplayableRecovery.MAX_CONSECUTIVE_VERDICT_SKIPS - 1,
            ),
        )
        assertFalse(
            UnplayableRecovery.allowsVerdictSkip(
                UnplayableRecovery.MAX_CONSECUTIVE_VERDICT_SKIPS,
            ),
        )
    }

    @Test
    fun `the definitive skip path itself stops at the verdict cap`() {
        assertEquals(
            UnplayableRecoveryAction.SKIP_NEXT,
            UnplayableRecovery.decide(
                definitive = true,
                hasAlternate = false,
                consecutiveVerdictSkips = 0,
            ),
        )
        assertEquals(
            UnplayableRecoveryAction.STOP,
            UnplayableRecovery.decide(
                definitive = true,
                hasAlternate = false,
                consecutiveVerdictSkips = UnplayableRecovery.MAX_CONSECUTIVE_VERDICT_SKIPS,
            ),
        )
        // Another upload is a different id, so it may still rescue the row.
        assertEquals(
            UnplayableRecoveryAction.SWAP_ALTERNATE,
            UnplayableRecovery.decide(
                definitive = true,
                hasAlternate = true,
                consecutiveVerdictSkips = UnplayableRecovery.MAX_CONSECUTIVE_VERDICT_SKIPS,
            ),
        )
    }
}
