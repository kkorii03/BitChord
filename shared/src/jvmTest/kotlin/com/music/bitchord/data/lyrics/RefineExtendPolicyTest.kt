package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A refine is given a longer listen when the lyrics outrun what was heard, or
 * when the drift it applied is only known to the transcript's end.
 */
class RefineExtendPolicyTest {

    private fun result(
        applied: Boolean = false,
        lines: Int = 5,
        slopePerMin: Double = 0.0,
        model: RefinePolicy.Model = RefinePolicy.Model.CONST,
    ) = RefinePolicy.Result(
        applied = applied,
        medianMs = 0L,
        madMs = 0L,
        lines = lines,
        slopePerMin = slopePerMin,
        model = model,
    )

    @Test
    fun anAppliedConstantMoveWithEveryLineInsideTheListenIsNotExtended() {
        assertFalse(RefineExtendPolicy.shouldExtend(result(applied = true), 90.0, 100_000L))
    }

    @Test
    fun aDriftingSongNeedsALongerListenButASettledOneDoesNot() {
        val drifting = result(
            applied = true,
            lines = 20,
            slopePerMin = 200.0,
            model = RefinePolicy.Model.LINEAR,
        )
        assertTrue(RefineExtendPolicy.shouldExtend(drifting, 90.0, 80_000L))
        assertTrue(
            RefineExtendPolicy.shouldExtend(
                drifting.copy(model = RefinePolicy.Model.DRIFT),
                90.0,
                80_000L,
            ),
        )
        assertFalse(RefineExtendPolicy.shouldExtend(result(applied = true, lines = 20), 90.0, 80_000L))
    }

    @Test
    fun twelveMatchedLinesNeedNoWiderWindow() {
        assertFalse(RefineExtendPolicy.shouldExtend(result(lines = 12), 90.0, 200_000L))
    }

    @Test
    fun aLastLineInsideTheHeardWindowIsNotExtended() {
        assertFalse(RefineExtendPolicy.shouldExtend(result(), 90.0, 100_000L))
    }

    @Test
    fun fiveLinesWithTheLastLineAtTwoHundredSecondsIsExtended() {
        assertTrue(RefineExtendPolicy.shouldExtend(result(), 90.0, 200_000L))
    }

    @Test
    fun anAlreadyExtendedListenIsNotRepeated() {
        assertFalse(
            RefineExtendPolicy.shouldExtend(
                result(applied = true, slopePerMin = 200.0, model = RefinePolicy.Model.LINEAR),
                RefineExtendPolicy.MAX_EXTEND_SECONDS,
                400_000L,
            ),
        )
    }

    @Test
    fun audioShortOfTheTargetDefersTheExtend() {
        assertFalse(RefineExtendPolicy.hasAudioFor(targetSeconds = 150.0, availableSeconds = 120.0))
    }

    @Test
    fun audioPastTheTargetExtends() {
        assertTrue(RefineExtendPolicy.hasAudioFor(targetSeconds = 150.0, availableSeconds = 160.0))
    }

    @Test
    fun audioExactlyAtTheTargetExtends() {
        assertTrue(RefineExtendPolicy.hasAudioFor(targetSeconds = 150.0, availableSeconds = 150.0))
    }

    @Test
    fun aFullyCachedFileWithNoStatedLengthExtends() {
        assertTrue(
            RefineExtendPolicy.hasAudioFor(
                targetSeconds = 180.0,
                availableSeconds = Double.POSITIVE_INFINITY,
            ),
        )
    }

    @Test
    fun aHalfCachedStreamReportsHalfItsLength() {
        assertEquals(
            75.0,
            RefineExtendPolicy.cachedSeconds(cachedPrefix = 500L, contentLength = 1_000L, durationSeconds = 150.0),
        )
    }

    @Test
    fun anUnknownLengthReportsMinusOne() {
        assertEquals(-1.0, RefineExtendPolicy.cachedSeconds(cachedPrefix = 500L, contentLength = 0L, durationSeconds = 150.0))
        assertEquals(
            -1.0,
            RefineExtendPolicy.cachedSeconds(
                cachedPrefix = 500L,
                contentLength = 1_000L,
                durationSeconds = Double.NaN,
            ),
        )
    }

    @Test
    fun aHalfCachedStreamDoesNotExtend() {
        val available = RefineExtendPolicy.cachedSeconds(500L, 1_000L, 150.0)
        assertFalse(RefineExtendPolicy.hasAudioFor(targetSeconds = 150.0, availableSeconds = available))
    }
}
