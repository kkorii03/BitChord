package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis-head retry rule: a round that added nothing is not started
 * again (three "came up short: held=0" rounds for one track in two seconds),
 * while real progress may still be retried up to the round limit.
 */
class AnalysisHeadRetryTest {

    private val min = 800L * 1024

    @Test
    fun roundWithoutProgressIsNotRetried() {
        assertFalse(analysisHeadShouldRetry(heldBefore = 0, heldAfter = 0, roundsStarted = 1, maxRounds = 3, minBytes = min))
    }

    @Test
    fun partialProgressMayBeRetried() {
        assertTrue(analysisHeadShouldRetry(heldBefore = 0, heldAfter = 400_000, roundsStarted = 1, maxRounds = 3, minBytes = min))
    }

    @Test
    fun progressThenStallStops() {
        assertFalse(analysisHeadShouldRetry(heldBefore = 400_000, heldAfter = 400_000, roundsStarted = 2, maxRounds = 3, minBytes = min))
    }

    @Test
    fun roundLimitStopsEvenWithProgress() {
        assertFalse(analysisHeadShouldRetry(heldBefore = 400_000, heldAfter = 700_000, roundsStarted = 3, maxRounds = 3, minBytes = min))
    }

    @Test
    fun completeHeadNeedNotRetry() {
        assertFalse(analysisHeadShouldRetry(heldBefore = 0, heldAfter = min, roundsStarted = 1, maxRounds = 3, minBytes = min))
    }

    @Test
    fun aHeadDeferredToAnotherWriterDoesNotConsumeTheClaim() {
        assertFalse(analysisHeadMayClaim(alreadyRequested = false, anotherWriterHas = true, inFlight = false))
        assertTrue(analysisHeadMayClaim(alreadyRequested = false, anotherWriterHas = false, inFlight = false))
        assertFalse(analysisHeadMayClaim(alreadyRequested = true, anotherWriterHas = false, inFlight = false))
        assertFalse(analysisHeadMayClaim(alreadyRequested = true, anotherWriterHas = true, inFlight = false))
    }

    @Test
    fun aHeadWhoseRoundIsStillInFlightIsNotReclaimedAfterItsClaimWasCleared() {
        // discardBadRendition/remove/purge clear analysisHeads but not the
        // in-flight set. Re-claiming here used to fail the in-flight add and
        // leave a claim with no round, so the analyzer never retried.
        assertFalse(analysisHeadMayClaim(alreadyRequested = false, anotherWriterHas = false, inFlight = true))
        assertFalse(analysisHeadMayClaim(alreadyRequested = true, anotherWriterHas = false, inFlight = true))
    }

    @Test
    fun aCompleteHeadNeedsNoRoundEvenWhenTheClaimWasReleased() {
        assertEquals(
            AnalysisHeadClear.AlreadyComplete,
            analysisHeadClearOutcome(held = 2_000_000, want = 1_000_000, removalSucceeded = false),
        )
    }

    @Test
    fun aPartialHeadHeldByAReaderIsDistinguishedFromACompleteOne() {
        // Both used to return false. Only the held case must release the claim;
        // the complete case is genuinely done.
        assertEquals(
            AnalysisHeadClear.HeldElsewhere,
            analysisHeadClearOutcome(held = 400_000, want = 1_000_000, removalSucceeded = false),
        )
        assertEquals(
            AnalysisHeadClear.Proceed,
            analysisHeadClearOutcome(held = 400_000, want = 1_000_000, removalSucceeded = true),
        )
    }

    @Test
    fun anEmptyHeadAlwaysProceeds() {
        assertEquals(
            AnalysisHeadClear.Proceed,
            analysisHeadClearOutcome(held = 0, want = 1_000_000, removalSucceeded = false),
        )
    }
}
