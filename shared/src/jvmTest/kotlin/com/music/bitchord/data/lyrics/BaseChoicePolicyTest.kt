package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The base choice from the phone (11-02): a fixed measurement-first order let
 * a +1.77 s measurement overwrite a +0.06 s AI answer on one cover, and a wrong
 * measured -498 ms overwrite provider times that were already right on another.
 * The heard words say which candidate is on time.
 */
class BaseChoicePolicyTest {

    private val truth = List(12) { 10_000L + it * 5_000L }

    private fun startsAt(offsetMs: Long) = truth.map { it + offsetMs }

    private fun choice(
        default: String,
        none: Long = 0L,
        ai: Long? = null,
        measured: Long? = null,
        manual: Boolean = false,
    ): String {
        val candidates = buildList {
            add(BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, startsAt(none)))
            ai?.let { add(BaseChoicePolicy.Candidate(BaseChoicePolicy.AI, startsAt(it))) }
            measured?.let { add(BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, startsAt(it))) }
        }
        return BaseChoicePolicy.choose(default, truth, candidates, manual = manual).name
    }

    @Test
    fun aiOnTimeBeatsMeasuredOnePointSevenSevenSecondsLate() {
        // qzXaSNyGZxY: AI +0.06 s / 79%, measured +1.77 s / 3%.
        assertEquals(
            BaseChoicePolicy.AI,
            choice(
                default = BaseChoicePolicy.MEASURED,
                none = 2_600L,
                ai = 60L,
                measured = 1_770L,
            ),
        )
    }

    @Test
    fun providerOriginalBeatsMeasuredAlmostOneSecondLate() {
        // 5YVJmhUdrp4: provider -0.03 s / 91%, measured -498 ms overwrote it.
        assertEquals(
            BaseChoicePolicy.NONE,
            choice(
                default = BaseChoicePolicy.MEASURED,
                none = 0L,
                ai = 2_000L,
                measured = 740L,
            ),
        )
    }

    @Test
    fun correctMeasurementStays() {
        // Fv2dYWZ1G7g / US2I6tSRjcE: measured was the right one.
        assertEquals(
            BaseChoicePolicy.MEASURED,
            choice(
                default = BaseChoicePolicy.MEASURED,
                none = 1_100L,
                ai = -2_000L,
                measured = 0L,
            ),
        )
    }

    @Test
    fun tooFewHeardLinesKeepsTheFixedOrder() {
        val few = List(5) { 10_000L + it * 5_000L }
        val candidates = listOf(
            BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, few.map { it + 2_600L }),
            BaseChoicePolicy.Candidate(BaseChoicePolicy.AI, few),
            BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, few.map { it + 1_770L }),
        )
        assertEquals(
            BaseChoicePolicy.MEASURED,
            BaseChoicePolicy.choose(BaseChoicePolicy.MEASURED, few, candidates).name,
        )
    }

    @Test
    fun manualChoiceWinsOverBetterScores() {
        assertEquals(
            BaseChoicePolicy.AI,
            choice(
                default = BaseChoicePolicy.AI,
                none = 2_000L,
                ai = 2_000L,
                measured = 0L,
                manual = true,
            ),
        )
    }

    @Test
    fun switchNeedsAThreeHundredMillisecondMedianGain() {
        val candidates = listOf(
            BaseChoicePolicy.Candidate(BaseChoicePolicy.AI, truth.map { it + 250L }),
            BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, truth.map { it + 500L }),
        )
        // The AI answer is inside 0.3 s on every line, but its median gain is
        // only 250 ms: not enough to replace the fixed order's measurement.
        assertEquals(
            BaseChoicePolicy.MEASURED,
            BaseChoicePolicy.choose(BaseChoicePolicy.MEASURED, truth, candidates).name,
        )
    }

    @Test
    fun switchNeedsAFifteenPointRatioGain() {
        val measured = truth.mapIndexed { index, ms -> if (index < 9) ms else ms + 1_000L }
        val ai = truth.mapIndexed { index, ms -> if (index < 9) ms else ms + 300L }
        val candidates = listOf(
            BaseChoicePolicy.Candidate(BaseChoicePolicy.AI, ai),
            BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, measured),
        )
        // Both medians are 0 ms; the AI answer only adds 25%p of lines within
        // 0.3 s. The ratio gain alone does not switch.
        assertEquals(
            BaseChoicePolicy.MEASURED,
            BaseChoicePolicy.choose(BaseChoicePolicy.MEASURED, truth, candidates).name,
        )
    }

    @Test
    fun aPickTheWordsRefuseFallsBackToNone() {
        // 11-06 sVwYpHqdt3o: `none:86560/0% measured:87359/0% -> measured`.
        val candidates = listOf(
            BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, startsAt(86_560L)),
            BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, startsAt(87_359L)),
        )
        val pick = BaseChoicePolicy.choose(BaseChoicePolicy.MEASURED, truth, candidates)
        assertEquals(BaseChoicePolicy.NONE, pick.name)
        assertEquals("below threshold", pick.reason)
        // A refused AI pick too.
        assertEquals(BaseChoicePolicy.NONE, choice(default = BaseChoicePolicy.AI, none = 5_000L, ai = 4_000L))
        // Within what a refine can move, the measurement stays.
        val near = BaseChoicePolicy.choose(
            BaseChoicePolicy.MEASURED,
            truth,
            listOf(
                BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, startsAt(3_000L)),
                BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, startsAt(1_500L)),
            ),
        )
        assertEquals(BaseChoicePolicy.MEASURED, near.name)
        assertEquals(null, near.reason)
    }

    @Test
    fun tooFewHeardLinesCannotRefuseAPick() {
        val few = List(5) { 10_000L + it * 5_000L }
        val candidates = listOf(
            BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, few.map { it + 86_560L }),
            BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, few.map { it + 87_359L }),
        )
        assertEquals(
            BaseChoicePolicy.MEASURED,
            BaseChoicePolicy.choose(BaseChoicePolicy.MEASURED, few, candidates).name,
        )
    }

    @Test
    fun latenessSampleNeedsSixLinesAndSeventyPercentWithin() {
        assertTrue(
            BaseChoicePolicy.latenessSampleAllowed(BaseChoicePolicy.Score(6, 100L, 0.70)),
        )
        assertFalse(
            BaseChoicePolicy.latenessSampleAllowed(BaseChoicePolicy.Score(5, 100L, 1.0)),
        )
        assertFalse(
            BaseChoicePolicy.latenessSampleAllowed(BaseChoicePolicy.Score(10, 400L, 0.69)),
        )
    }

    @Test
    fun heardLineStartsComeFromTheTranscript() {
        val lines = listOf("hello from", "i must", "to tell")
        val words = listOf(
            LyricsWordAlign.Heard("hello", 1_000, 1_300),
            LyricsWordAlign.Heard("from", 1_300, 1_500),
            LyricsWordAlign.Heard("i", 5_000, 5_100),
            LyricsWordAlign.Heard("must", 5_100, 5_400),
            LyricsWordAlign.Heard("to", 9_000, 9_100),
            LyricsWordAlign.Heard("tell", 9_100, 9_500),
        )
        assertEquals(
            listOf<Long?>(1_000L, 5_000L, 9_000L),
            BaseChoicePolicy.heardStarts(lines, words),
        )
    }

    @Test
    fun aBaseThatPutsMostOfTheSheetBeforeTheSongStartsIsNotThisSheetSTiming() {
        // AMILJn51YBk (11-02): KuGou's 19 lines shown after a measured base.
        // The map was for another arrangement, and the screen had 7 of 19 lines
        // stacked at 0:00 for the whole song.
        val buried = listOf(
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 6_685L, 15_696L, 26_537L, 33_759L, 38_541L, 49_765L,
            57_866L, 66_203L, 74_883L, 82_092L, 111_691L, 119_370L,
        )
        assertTrue(BaseChoicePolicy.buriesSheet(buried))
        // The same sheet with a −400 ms correction: only its own first line
        // lands at zero, which every provider sheet does anyway.
        val nudged = listOf(
            0L, 34_210L, 40_300L, 45_840L, 56_630L, 62_560L, 68_250L, 78_340L, 89_250L, 100_490L,
            111_910L, 118_260L, 129_120L, 139_770L, 150_950L, 162_080L, 173_070L, 204_740L, 216_110L,
        )
        assertFalse(BaseChoicePolicy.buriesSheet(nudged))
        // A cover whose own intro the measurement pushed later: nothing buried.
        assertFalse(BaseChoicePolicy.buriesSheet(buried.map { it + 38_000L }))
        // Too few lines to call anything "most of the sheet".
        assertFalse(BaseChoicePolicy.buriesSheet(listOf(0L, 0L, 0L)))
        // Untimed lines are not evidence either way.
        assertFalse(BaseChoicePolicy.buriesSheet(listOf(null, null, null, null, null)))
    }
}
