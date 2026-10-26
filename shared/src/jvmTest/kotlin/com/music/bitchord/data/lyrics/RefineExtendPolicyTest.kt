package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A refine that found too few lines is given a longer listen only when the lyrics outrun it. */
class RefineExtendPolicyTest {

    private fun result(applied: Boolean = false, lines: Int = 5) =
        RefinePolicy.Result(applied = applied, medianMs = 0L, madMs = 0L, lines = lines)

    @Test
    fun anAppliedRefineIsNeverExtended() {
        assertFalse(RefineExtendPolicy.shouldExtend(result(applied = true), 90.0, 200_000L))
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
        assertFalse(RefineExtendPolicy.shouldExtend(result(), 180.0, 400_000L))
    }
}
