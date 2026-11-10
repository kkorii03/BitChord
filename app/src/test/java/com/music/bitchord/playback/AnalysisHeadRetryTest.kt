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

    @Test
    fun aPlayerReadWinsOverAnAnalysisWriterOnTheSameEntry() {
        // Regression: an analysis round writing the plain key made the
        // player's open wait for the whole 4.98 MB file, so first audio came
        // 28 ms after the round finished rather than when the URL resolved.
        assertTrue(
            analysisHeadMustYieldToPlayback(
                analysisWriteKey = "YedjjosYKmI",
                playbackReadKey = "YedjjosYKmI",
                roundInFlight = true,
                playerHoldsVideo = true,
            ),
        )
    }

    @Test
    fun anAltPlaybackReadDoesNotStopThePlainAnalysisRound() {
        assertFalse(
            analysisHeadMustYieldToPlayback(
                analysisWriteKey = "YedjjosYKmI",
                playbackReadKey = "YedjjosYKmI#alt",
                roundInFlight = true,
                playerHoldsVideo = true,
            ),
        )
        assertFalse(
            analysisHeadMustYieldToPlayback(
                analysisWriteKey = "YedjjosYKmI",
                playbackReadKey = "YedjjosYKmI",
                roundInFlight = false,
                playerHoldsVideo = true,
            ),
        )
    }

    @Test
    fun aClaimKeptWhileAReaderHeldThePartialIsNotRetriedUntilTheReaderLetsGo() {        // Dropping the claim on HeldElsewhere let the 250 ms idle tick launch
        // a round, resolve contentLength and retry the locked removeResource
        // for as long as the player/read-ahead held the entry.
        assertFalse(
            "a held claim must not start another round while the writer still has it",
            analysisHeadMayClaimOrRetry(
                alreadyRequested = true,
                heldForReader = true,
                anotherWriterHas = true,
                inFlight = false,
            ),
        )
        assertTrue(
            "the held claim may start its one deferred round once the writer lets go",
            analysisHeadMayClaimOrRetry(
                alreadyRequested = true,
                heldForReader = true,
                anotherWriterHas = false,
                inFlight = false,
            ),
        )
        assertFalse(
            "never stack a round on one still in flight",
            analysisHeadMayClaimOrRetry(
                alreadyRequested = true,
                heldForReader = true,
                anotherWriterHas = false,
                inFlight = true,
            ),
        )
        assertFalse(
            "a claim that was not held by a reader is still one round only",
            analysisHeadMayClaimOrRetry(
                alreadyRequested = true,
                heldForReader = false,
                anotherWriterHas = false,
                inFlight = false,
            ),
        )
    }

    @Test
    fun aReleaseThatArrivesWhileTheRoundIsUnwindingWaitsForItsCompletion() {
        // The release hook must not start a second round on top of one still
        // unwinding; the finally block runs the retry once inFlight is clear.
        assertFalse(
            analysisHeadHeldClaimMayRetry(
                heldForReader = true,
                anotherWriterHas = false,
                inFlight = true,
            ),
        )
        assertTrue(
            analysisHeadHeldClaimMayRetry(
                heldForReader = true,
                anotherWriterHas = false,
                inFlight = false,
            ),
        )
    }

    @Test
    fun aMixWaveformWithNoCopyAsksForTheAnalysisCopyUnlessLocalOrHeldOff() {
        assertTrue(mixWaveformShouldFetchCopy(copyMissing = true, local = false, heavyWorkAllowed = true, overheated = false))
        assertFalse(mixWaveformShouldFetchCopy(copyMissing = false, local = false, heavyWorkAllowed = true, overheated = false))
        assertFalse(mixWaveformShouldFetchCopy(copyMissing = true, local = true, heavyWorkAllowed = true, overheated = false))
        assertFalse(mixWaveformShouldFetchCopy(copyMissing = true, local = false, heavyWorkAllowed = false, overheated = false))
        assertFalse(mixWaveformShouldFetchCopy(copyMissing = true, local = false, heavyWorkAllowed = true, overheated = true))
    }

    @Test
    fun aMixWaveformReadsAPartialCopyWhoseLengthWasNeverRecorded() {
        // 11-11 phone: ItUC9TPcb08 held 2999880 bytes, BcjqWNViVU0 3849015,
        // both with total=0, and the only copy accepted was a complete one
        // (contentLength > 0), so both decks answered "none" in 2-4 ms.
        val seconds = { r: AudioCache.Rendition -> r.cachedPrefix / 20_000.0 }
        val plain = AudioCache.Rendition(key = "ItUC9TPcb08", contentLength = 0L, cachedPrefix = 2_999_880L)
        assertFalse(plain.isComplete)
        assertEquals(
            plain,
            mixWaveformPartialRendition(listOf(plain), "ItUC9TPcb08", "ItUC9TPcb08", emptySet(), seconds),
        )
    }

    @Test
    fun aMixWaveformTakesThePlayersOwnEntryButNoOtherSubstitute() {
        val seconds = { r: AudioCache.Rendition -> r.cachedPrefix / 20_000.0 }
        val plain = AudioCache.Rendition(key = "v", contentLength = 0L, cachedPrefix = 900_000L)
        val playing = AudioCache.Rendition(key = "v#alt", contentLength = 0L, cachedPrefix = 3_000_000L)
        val other = AudioCache.Rendition(key = "v#hifi", contentLength = 0L, cachedPrefix = 9_000_000L)
        val all = listOf(plain, playing, other)
        // The player's entry holds the late window it has already played.
        assertEquals(playing, mixWaveformPartialRendition(all, "v", "v#alt", emptySet(), seconds))
        // A substitute the player is not on is never read.
        assertEquals(plain, mixWaveformPartialRendition(all, "v", "v", emptySet(), seconds))
        // A copy already written off, or one with no prefix, is skipped.
        assertEquals(plain, mixWaveformPartialRendition(all, "v", "v#alt", setOf("v#alt"), seconds))
        assertEquals(
            null,
            mixWaveformPartialRendition(
                listOf(AudioCache.Rendition("v", 0L, 0L)),
                "v",
                "v",
                emptySet(),
                seconds,
            ),
        )
    }

    @Test
    fun aReleaseAfterTheClaimIsKeptStartsExactlyOneDeferredRound() {
        // Held for a reader, round already gone: the release event owns the
        // retry whether or not the claim map still has it (as v1.9), a claim
        // not held for a reader is not this hook's, and a writer that still
        // holds the entry defers again.
        assertTrue(
            analysisHeadHeldClaimMayRetry(
                heldForReader = true,
                anotherWriterHas = false,
                inFlight = false,
            ),
        )
        assertFalse(
            analysisHeadHeldClaimMayRetry(
                heldForReader = false,
                anotherWriterHas = false,
                inFlight = false,
            ),
        )
        assertFalse(
            analysisHeadHeldClaimMayRetry(
                heldForReader = true,
                anotherWriterHas = true,
                inFlight = false,
            ),
        )
    }
}
