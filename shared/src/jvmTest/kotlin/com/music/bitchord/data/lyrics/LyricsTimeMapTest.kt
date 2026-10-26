package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The record-to-video clock, its knot chains, and the retiming of every timed part of a line. */
class LyricsTimeMapTest {

    @Test
    fun aPlainShiftMovesEveryMoment() {
        val map = LyricsTimeMap.shift(-1_500L)
        assertEquals(1, map.knots)
        assertEquals(-1_500L, map.map(0L))
        assertEquals(8_500L, map.map(10_000L))
        assertEquals(listOf(0L, -1_500L), map.flatten())
        assertEquals(1.0, map.headRate)
        assertEquals(1.0, map.tailRate)
    }

    @Test
    fun aLinearMapAppliesItsRateAndOffset() {
        val map = LyricsTimeMap.linear(1.5, -200L)
        assertEquals(-200L, map.map(0L))
        assertEquals(1_300L, map.map(1_000L))
        assertEquals(1.0, LyricsTimeMap.linear(1.0, 0L).headRate)
    }

    @Test
    fun anImpossibleRateIsRefused() {
        listOf(0.0, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { rate ->
            assertFailsWith<IllegalArgumentException>(rate.toString()) {
                LyricsTimeMap.linear(rate, 0L)
            }
        }
    }

    @Test
    fun aKnotChainExtrapolatesAtTheNearestEndsPace() {
        val map = assertNotNull(
            LyricsTimeMap.through(
                src = listOf(0L, 1_000L, 2_000L),
                dst = listOf(500L, 1_500L, 3_500L),
                headRate = 1.0,
                tailRate = 2.0,
            ),
        )
        assertEquals(-500L, map.map(-1_000L))
        assertEquals(500L, map.map(0L))
        assertEquals(1_000L, map.map(500L))
        assertEquals(1_500L, map.map(1_000L))
        assertEquals(2_500L, map.map(1_500L))
        assertEquals(3_500L, map.map(2_000L))
        assertEquals(5_500L, map.map(3_000L))
    }

    @Test
    fun theHeadRateIsUsedBeforeTheFirstKnot() {
        val map = assertNotNull(LyricsTimeMap.through(listOf(1_000L), listOf(2_000L), headRate = 2.0, tailRate = 3.0))
        assertEquals(0L, map.map(0L))
        assertEquals(1_000L, map.map(500L))
        assertEquals(2_000L, map.map(1_000L))
        assertEquals(5_000L, map.map(2_000L))
    }

    @Test
    fun interpolationRoundsToTheNearestMillisecond() {
        val map = assertNotNull(LyricsTimeMap.through(listOf(0L, 1_000L), listOf(0L, 1_001L), 1.0))
        assertEquals(501L, map.map(500L))
    }

    @Test
    fun outOfOrderKnotsAreDropped() {
        val map = assertNotNull(
            LyricsTimeMap.through(
                src = listOf(0L, 10L, 10L, 20L, 30L),
                dst = listOf(0L, 10L, 20L, 15L, 40L),
                headRate = 1.0,
            ),
        )
        assertEquals(4, map.knots)
        assertEquals(10L, map.map(10L))
        assertEquals(15L, map.map(20L))
    }

    @Test
    fun aKnotThatWouldRunBackwardsIsDropped() {
        val map = assertNotNull(LyricsTimeMap.through(listOf(0L, 10L, 20L), listOf(0L, 20L, 15L), 1.0))
        assertEquals(2, map.knots)
        assertEquals(20L, map.map(10L))
    }

    @Test
    fun aFlatStretchIsStillNonDecreasing() {
        val map = assertNotNull(LyricsTimeMap.through(listOf(0L, 10L, 20L), listOf(0L, 10L, 10L), 1.0))
        assertEquals(3, map.knots)
        assertEquals(10L, map.map(15L))
    }

    @Test
    fun throughRefusesMismatchedOrEmptyInputsAndBadRates() {
        assertNull(LyricsTimeMap.through(emptyList(), emptyList(), 1.0))
        assertNull(LyricsTimeMap.through(listOf(0L), listOf(0L, 1L), 1.0))
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertNull(LyricsTimeMap.through(listOf(0L), listOf(0L), bad), "head $bad")
            assertNull(LyricsTimeMap.through(listOf(0L), listOf(0L), 1.0, bad), "tail $bad")
        }
    }

    @Test
    fun flattenAndUnflattenAreInverses() {
        val map = assertNotNull(
            LyricsTimeMap.through(listOf(0L, 1_000L, 2_000L), listOf(300L, 1_400L, 3_000L), 1.1, 0.9),
        )
        assertEquals(map, LyricsTimeMap.unflatten(map.flatten(), map.headRate, map.tailRate))
        assertEquals(listOf(0L, 300L, 1_000L, 1_400L, 2_000L, 3_000L), map.flatten())
    }

    @Test
    fun unflattenRefusesOddAndEmptyLists() {
        assertNull(LyricsTimeMap.unflatten(emptyList(), 1.0, 1.0))
        assertNull(LyricsTimeMap.unflatten(listOf(0L, 1L, 2L), 1.0, 1.0))
        assertNull(LyricsTimeMap.unflatten(listOf(0L, 1L), 0.0, 1.0))
        assertNull(LyricsTimeMap.unflatten(listOf(0L, 1L), 1.0, Double.NaN))
    }

    @Test
    fun mapsWithTheSameNumbersAreEqual() {
        val a = LyricsTimeMap.linear(1.1, 100L)
        val b = LyricsTimeMap.linear(1.1, 100L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, LyricsTimeMap.linear(1.1, 101L))
        assertNotEquals(a, LyricsTimeMap.linear(1.2, 100L))
        assertTrue(a.toString().contains("knots=1"))
        assertTrue(a.toString().contains("head=1.1"))
    }

    @Test
    fun aNullMapAndNoCorrectionReturnTheSameLines() {
        val line = LyricLine(1_000L, "hello")
        assertSame(line, listOf(line).timedBy(null, 0L).single())
    }

    @Test
    fun aCorrectionMovesAndClampsTheWholeLine() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "hello",
            words = listOf(
                LyricWord(
                    startMs = 1_000L,
                    endMs = 2_000L,
                    text = "hello",
                    syllables = listOf(LyricSyllable(1_000L, 1_500L, 0, 2), LyricSyllable(1_500L, 2_000L, 2, 5)),
                ),
            ),
            sungUntilMs = 2_500L,
            background = LyricLine(1_200L, "(ooh)"),
            alignment = LyricAlignment.End,
            timingSource = LyricLine(900L, "안녕"),
            wordsEstimated = true,
        )
        val moved = listOf(line).timedBy(null, extraMs = -3_000L).single()
        assertEquals(0L, moved.timeMs)
        assertEquals(0L, moved.words.single().startMs)
        assertEquals(0L, moved.words.single().endMs)
        assertEquals(0L, moved.words.single().syllables.first().startMs)
        assertEquals(0L, moved.words.single().syllables.last().endMs)
        assertEquals(0L, moved.sungUntilMs)
        assertEquals(0L, moved.background?.timeMs)
        assertEquals(0L, moved.timingSource?.timeMs)
        assertEquals(LyricAlignment.End, moved.alignment)
        assertTrue(moved.wordsEstimated)
    }

    @Test
    fun aMappedLineMovesItsWordsBackgroundAndTimingSourceTogether() {
        val line = LyricLine(
            timeMs = 10_000L,
            text = "hello",
            words = listOf(LyricWord(10_000L, 11_000L, "hello")),
            sungUntilMs = 11_000L,
            background = LyricLine(11_500L, "(ooh)"),
            timingSource = LyricLine(9_500L, "안녕"),
        )
        val moved = listOf(line).timedBy(LyricsTimeMap.shift(1_000L), extraMs = 500L).single()
        assertEquals(11_500L, moved.timeMs)
        assertEquals(11_500L, moved.words.single().startMs)
        assertEquals(12_500L, moved.words.single().endMs)
        assertEquals(12_500L, moved.sungUntilMs)
        assertEquals(13_000L, moved.background?.timeMs)
        assertEquals(11_000L, moved.timingSource?.timeMs)
    }

    @Test
    fun aWordEndNeverPrecedesItsStartAfterClamping() {
        val line = LyricLine(
            timeMs = 1_000L,
            text = "hi",
            words = listOf(LyricWord(1_000L, 2_000L, "hi")),
        )
        val moved = listOf(line).timedBy(LyricsTimeMap.shift(-2_500L)).single()
        assertEquals(0L, moved.words.single().startMs)
        assertEquals(0L, moved.words.single().endMs)
    }

    // ---- TimingFit ---------------------------------------------------------------------

    @Test
    fun medianHandlesOddEvenAndUnsortedValues() {
        assertEquals(2.0, TimingFit.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, TimingFit.median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(7.0, TimingFit.median(listOf(7.0)))
    }

    @Test
    fun aCustomMinimumAnswerCountIsHonoured() {
        val points = listOf(0L, 10_000L, 20_000L, 30_000L).map { it to it }
        assertNull(TimingFit.fit(points.take(3), minPoints = 4))
        assertNotNull(TimingFit.fit(points, minPoints = 4))
    }

    @Test
    fun anAnswerExactlyAtTheInlierEdgeStillAgrees() {
        val atEdge = listOf(0L to 0L, 10_000L to 10_000L, 20_000L to 21_500L, 30_000L to 30_000L)
        val line = assertNotNull(TimingFit.fit(atEdge))
        assertEquals(1.0, line.rate)
        assertEquals(4, line.inliers)

        val justOver = listOf(0L to 0L, 10_000L to 10_000L, 20_000L to 21_501L, 30_000L to 30_000L)
        val refined = assertNotNull(TimingFit.fit(justOver))
        assertEquals(1.0, refined.rate)
        assertEquals(3, refined.inliers)
        assertEquals(4, refined.answered)
    }

    @Test
    fun thePlausiblePaceRangeIsInclusiveAtBothEnds() {
        fun lineAt(rate: Double) =
            listOf(0L, 10_000L, 20_000L, 30_000L).map { it to (it * rate).toLong() }

        assertEquals(0.75, assertNotNull(TimingFit.fit(lineAt(0.75))).rate)
        assertEquals(1.35, assertNotNull(TimingFit.fit(lineAt(1.35))).rate)
        assertNull(TimingFit.fit(lineAt(0.74)))
        assertNull(TimingFit.fit(lineAt(1.36)))
    }

    @Test
    fun aPaceWithinTheSnapWindowBecomesTheRecordsPace() {
        val snapped = listOf(0L to 0L, 10_000L to 9_950L, 20_000L to 19_900L, 30_000L to 29_850L)
        assertEquals(1.0, assertNotNull(TimingFit.fit(snapped)).rate)

        val justOutside = listOf(0L to 0L, 10_000L to 9_940L, 20_000L to 19_880L, 30_000L to 29_820L)
        assertEquals(0.994, assertNotNull(TimingFit.fit(justOutside)).rate)
    }

    @Test
    fun anAnswerSetWithNoPaceInformationIsAPlainShift() {
        val line = assertNotNull(
            TimingFit.fit(listOf(10_000L to 1_000L, 10_000L to 2_000L, 10_000L to 3_000L)),
        )
        assertEquals(1.0, line.rate)
        assertEquals(-8_000L, line.offsetMs)
        assertEquals(3, line.inliers)
    }

    @Test
    fun aNegativePaceIsNotAFit() {
        val points = listOf(0L, 10_000L, 20_000L, 30_000L).map { it to -it }
        assertNull(TimingFit.fit(points))
    }

    @Test
    fun aHalfMillisecondOffsetRoundsToTheNearestMillisecond() {
        val line = assertNotNull(TimingFit.fit(listOf(0L to 0L, 10_000L to 10_001L), minPoints = 2))
        assertEquals(1.0, line.rate)
        assertEquals(1L, line.offsetMs)
        assertEquals(1L, line.map.map(0L))
        assertEquals(10_001L, line.map.map(10_000L))
    }

    @Test
    fun aFittedMapIsNonDecreasingEverywhere() {
        val points = listOf(0L to 0L, 10_000L to 9_940L, 20_000L to 19_880L, 30_000L to 29_820L)
        val line = assertNotNull(TimingFit.fit(points))
        var previous = Long.MIN_VALUE
        for (at in -10_000L..40_000L step 1_000L) {
            val mapped = line.map.map(at)
            assertTrue(mapped >= previous, "map went backwards at $at")
            assertTrue(abs(mapped - (line.rate * at + line.offsetMs).toLong()) <= 1)
            previous = mapped
        }
    }
}
