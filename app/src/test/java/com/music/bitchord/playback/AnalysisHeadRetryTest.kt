package com.music.bitchord.playback

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
}
