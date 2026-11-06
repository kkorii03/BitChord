package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The last line-level correction: one move, or one straight drift, only when a whole song agrees. */
class RefinePolicyTest {

    private fun line(at: Long, text: String) = LyricLine(timeMs = at, text = text)

    private fun heard(text: String, at: Long) = LyricsWordAlign.Heard(text, at, at + 300)

    private fun lines(count: Int, startMs: Long = 10_000L, stepMs: Long = 10_000L): List<LyricLine> =
        List(count) { index -> line(startMs + index * stepMs, "word$index") }

    private fun heardFor(lines: List<LyricLine>, residualMs: Long): List<LyricsWordAlign.Heard> =
        lines.mapIndexed { index, line -> heard("word$index", line.timeMs + residualMs) }

    @Test
    fun aConstantEarlyLineIsMovedLater() {
        val lines = lines(8)
        val result = RefinePolicy.compute(lines, heardFor(lines, 200L))

        assertTrue(result.applied)
        assertEquals(200L, result.medianMs)
        assertEquals(0L, result.madMs)
        assertEquals(8, result.lines)
    }

    @Test
    fun aSmallTightMedianVerifiesWithoutMovingTheLines() {
        val lines = lines(8)
        val result = RefinePolicy.compute(lines, heardFor(lines, 68L))

        assertFalse(result.applied)
        assertEquals(68L, result.medianMs)
        assertTrue(result.verified)
    }

    @Test
    fun aLargeMoveThatWasAppliedIsNotReportedAsVerified() {
        val lines = lines(8)
        val result = RefinePolicy.compute(lines, heardFor(lines, 200L))

        assertTrue(result.applied)
        assertFalse(result.verified)
    }

    @Test
    fun fewerThanSixLinesIsNotASongWideAnswer() {
        val lines = lines(5)
        val result = RefinePolicy.compute(lines, heardFor(lines, 200L))

        assertFalse(result.applied)
        assertEquals(5, result.lines)
    }

    @Test
    fun aRepeatedChorusTakesTheNearerHearing() {
        val lines = lines(6)
        val heard = buildList {
            lines.forEachIndexed { index, line ->
                add(heard("word$index", line.timeMs + 1_400L))
                add(heard("word$index", line.timeMs + 200L))
            }
        }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(200L, result.medianMs)
    }

    @Test
    fun scatteredResidualsAreNotOneOffset() {
        val residuals = listOf(200L, 200L, 200L, 600L, -200L, -200L, 1_000L)
        val lines = lines(residuals.size)
        val heard = lines.mapIndexed { index, line -> heard("word$index", line.timeMs + residuals[index]) }
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied)
        assertEquals(400L, result.madMs)
    }

    @Test
    fun oneRefineMovesNoFurtherThanTheCap() {
        // The matching window is ±1.5 s, so a song-wide median can only reach
        // the cap at its edge; it is never allowed past it.
        val lines = lines(6)
        val result = RefinePolicy.compute(lines, heardFor(lines, 1_500L))

        assertTrue(result.applied)
        assertEquals(1_500L, result.medianMs)
    }

    @Test
    fun japaneseLinesWithoutSpacesAreMatchedByTheirOpeningLetters() {
        // The reported failure: Whisper hears a whole unspaced Japanese line as
        // one word, so "the first word" never equals a heard word. The first
        // four letters do match, and every line sits 200 ms early.
        val texts = listOf(
            "ダンスホール",
            "フォニイ",
            "夜に駆ける",
            "残酷な天使のテーゼ",
            "紅蓮華",
            "群青",
        )
        val lines = texts.mapIndexed { index, text -> line(10_000L + index * 10_000L, text) }
        val heard = lines.map { line -> heard(line.text, line.timeMs + 200L) }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(200L, result.medianMs)
        assertEquals(0.0, result.slopePerMin)
        assertEquals(lines.size, result.lines)
    }

    @Test
    fun aSteadyDriftIsFittedAsALine() {
        // 260 ms per minute over 63 s moves the last line 273 ms: below the
        // evidence bar but above MIN_SLOPE, so the refine still keeps the rate.
        val startMs = 10_000L
        val stepMs = 7_000L
        val slopePerMs = 260.0 / 60_000.0
        val lines = List(10) { index -> line(startMs + index * stepMs, "line$index") }
        val heard = lines.map { line ->
            heard(line.text, line.timeMs + (slopePerMs * line.timeMs).toLong())
        }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.LINEAR, result.model)
        assertTrue(abs(result.slopePerMin - 260.0) <= 50.0, "slope ${result.slopePerMin} ms/min")
        assertEquals(lines.size, result.lines)
    }

    @Test
    fun aSmallDriftOverAShortStretchIsOnlyAConstantMove() {
        // 0.1 s/min over 63 s moves the last line just 105 ms: the evidence
        // does not support a rate, so the constant move is what applies.
        val startMs = 10_000L
        val stepMs = 7_000L
        val slopePerMs = 100.0 / 60_000.0
        val lines = List(10) { index -> line(startMs + index * stepMs, "line$index") }
        val heard = lines.map { line ->
            heard(line.text, line.timeMs + (slopePerMs * line.timeMs).toLong())
        }
        val result = RefinePolicy.compute(lines, heard)

        assertEquals(RefinePolicy.Model.CONST, result.model)
        assertEquals(0.0, result.slopePerMin)
    }

    @Test
    fun aSmallDriftOverTheWholeSongIsARate() {
        // The same 0.1 s/min over 400 s moves the last line 667 ms: the whole
        // song supports the rate, so it is applied (and from the first line).
        val startMs = 10_000L
        val stepMs = 14_000L
        val slopePerMs = 100.0 / 60_000.0
        val lines = List(30) { index -> line(startMs + index * stepMs, "line$index") }
        val heard = lines.map { line ->
            heard(line.text, line.timeMs + (slopePerMs * line.timeMs).toLong())
        }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.LINEAR, result.model)
        assertTrue(abs(result.slopePerMin - 100.0) <= 10.0, "slope ${result.slopePerMin} ms/min")
    }

    @Test
    fun aSongWideLatenessIsMovedByTheWideWindow() {
        // Q7Q-ul7AlIk: every line is about 5.2 s late because the provider's
        // file was timed to another arrangement. The narrow window sees
        // nothing; the wide one must find the move all twelve lines share.
        val texts = listOf(
            "alpha", "bravo", "cabin", "delta", "eagle", "fable",
            "giant", "hazel", "ivory", "joker", "karma", "lemon",
        )
        val lines = texts.mapIndexed { index, text -> line(10_000L + index * 10_000L, text) }
        val heard = lines.mapIndexed { index, line -> heard(texts[index], line.timeMs + 5_200L) }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.WIDE, result.model)
        assertTrue(abs(result.medianMs - 5_200L) <= 100L, "median ${result.medianMs} ms")
        assertEquals(12, result.lines)
    }

    @Test
    fun aRepeatedChorusEveryFourSecondsIsNotAWideMove() {
        // Each line is heard at its shown time and again 4 s either side. The
        // true move is zero, and the wide window must not pick one of the
        // repeats just because it also has support.
        val lines = List(12) { index -> line(10_000L + index * 10_000L, "chorus") }
        val heard = buildList {
            lines.forEach { line ->
                add(heard("chorus", line.timeMs))
                add(heard("chorus", line.timeMs + 4_000L))
                add(heard("chorus", line.timeMs - 4_000L))
            }
        }
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied)
    }

    @Test
    fun eightSupportingLinesAreNotEnoughForTheWideWindow() {
        // Twelve lines match, but only eight agree on the 5.2 s move; ten are
        // needed before a song-wide arrangement shift may be applied.
        val texts = listOf(
            "alpha", "bravo", "cabin", "delta", "eagle", "fable",
            "giant", "hazel", "ivory", "joker", "karma", "lemon",
        )
        val lines = texts.mapIndexed { index, text -> line(10_000L + index * 10_000L, text) }
        val heard = lines.mapIndexed { index, line ->
            heard(texts[index], line.timeMs + if (index < 8) 5_200L else 0L)
        }
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied)
    }

    @Test
    fun aSheetThatDriftsPastTheNarrowWindowIsFittedAsALine() {
        // 11-04 WxYgXmZ9xh8: the provider's file was timed to another edit, so
        // the error grew from +1.8 s to +6.8 s across the song. Every line is
        // more than the narrow window's 1.5 s out, so the narrow pass measures
        // none of them and the slope can never be seen; the whole-song alignment
        // sees it, and one straight line is applied instead of nothing.
        val startMs = 20_000L
        val stepMs = 12_000L
        val slopePerMs = 2_000.0 / 60_000.0
        val lines = List(12) { index -> line(startMs + index * stepMs, "line$index") }
        val heard = lines.map { line ->
            heard(line.text, line.timeMs + 1_800L + (slopePerMs * line.timeMs).toLong())
        }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.DRIFT, result.model)
        assertEquals(12, result.lines)
        assertTrue(abs(result.slopePerMin - 2_000.0) <= 100.0, "slope ${result.slopePerMin} ms/min")
        assertTrue(abs(result.medianMs - 1_800L) <= 50L, "offset ${result.medianMs} ms")
    }

    @Test
    fun twoMatchingLettersAreNotEnoughToMoveTheSheet() {
        // Sol review 11-04: placeLines places a line on as little as two opening
        // letters, so eight lines of "abcdef" against eight "abzzzz" gave a
        // perfect fit — offset 9.7 s, 1000 ms/min — and moved the whole sheet on
        // nonsense text. Only lines whose own letters the transcript mostly
        // matched may feed the drift.
        val lines = List(8) { index -> line(20_000L + index * 12_000L, "abcdef") }
        val heard = List(8) { index -> heard("abzzzz", 30_000L + index * 12_200L) }
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied)
        assertEquals(0.0, result.slopePerMin)
    }

    @Test
    fun aTightConstantPastTheWideWindowIsStillFound() {
        // 11-04 OmEqDBr1GvE: the sheet sits 8.4 s off, flat. The wide window
        // used to look only 8 s out, under the 10 s cap it allows, so the move
        // was never seen; the whole-song alignment's flat fit finds it.
        val lines = List(12) { index -> line(20_000L + index * 12_000L, "line$index") }
        val heard = lines.map { heard(it.text, it.timeMs - 8_400L) }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.WIDE, result.model)
        assertEquals(-8_400L, result.medianMs)
    }

    @Test
    fun anAiAnswerIsNotFittedAgainstItself() {
        // Gemini's "words" are its own line times: placed against the lines they
        // are a perfect straight line, which says nothing about the video.
        val lines = List(12) { index -> line(20_000L + index * 12_000L, "line$index") }
        val heard = lines.map { heard(it.text, it.timeMs + 1_800L + it.timeMs / 30) }
        val result = RefinePolicy.compute(lines, heard, lineLevelWords = true)

        assertFalse(result.applied)
    }

    @Test
    fun anEvenlyLateSheetIsOneMoveAndNotADrift() {
        // The same song one constant 3 s late: no slope in the points, so the
        // wide window's single move stays what applies.
        val lines = List(12) { index -> line(20_000L + index * 12_000L, "line$index") }
        val heard = lines.map { heard(it.text, it.timeMs + 3_000L) }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.WIDE, result.model)
        assertEquals(0.0, result.slopePerMin)
        assertEquals(3_000L, result.medianMs)
    }

    /** Six letters no other line shares, so a line can only ever match its own hearing. */
    private fun uniqueText(index: Int): String =
        String(CharArray(6) { offset -> Char(0xAC00 + index * 6 + offset) })

    @Test
    fun anEditDriftWithOrdinaryScatterIsStillADrift() {
        // Review 11-06: ±130 ms of ordinary scatter on a 1.2 s/min drift. The
        // line nearest the median has no constant-move error at all, so one
        // median of scatter was less than its own jitter and the whole drift
        // was refused.
        val jitter = listOf(60L, -80L, 40L, -120L, 90L, -30L, -130L, 70L, -50L, 110L, -90L, 30L, -60L)
        val lines = List(13) { index -> line(20_000L + index * 12_000L, "line$index") }
        val heard = lines.mapIndexed { index, line ->
            heard(line.text, line.timeMs + 1_800L + line.timeMs / 50 + jitter[index])
        }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.DRIFT, result.model)
        assertTrue(abs(result.slopePerMin - 1_200.0) <= 100.0, "slope ${result.slopePerMin} ms/min")
        assertTrue(abs(result.medianMs - 1_800L) <= 100L, "offset ${result.medianMs} ms")
    }

    @Test
    fun linesSittingOnTheConstantMoveStillRefuseTheDrift() {
        // What the guard is for: a fifth of the lines are right on one constant
        // move and the drift would throw them more than a second off.
        val jitter = listOf(60L, -80L, 40L, -120L, 90L, -30L, -130L, 70L, -50L, 110L, -90L, 30L, -60L, 50L, -70L)
        val lines = List(15) { index -> line(20_000L + index * 12_000L, "line$index") }
        val heard = lines.mapIndexed { index, line ->
            val moved = if (index < 3) 4_000L else 1_800L + line.timeMs / 50 + jitter[index]
            heard(line.text, line.timeMs + moved)
        }
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied)
    }

    @Test
    fun aDriftPastTheSlopeCapIsNotClippedToTheCap() {
        // Review 11-06: the lines still inside the narrow window said 1.2 s/min,
        // and it was clipped to 600 ms/min and applied, half the real drift.
        // Refused here, the whole-song alignment measures the real one.
        val lines = List(59) { index -> line(10_000L + index * 5_000L, uniqueText(index)) }
        val heard = lines.map { heard(it.text, it.timeMs + it.timeMs / 50) }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertEquals(RefinePolicy.Model.DRIFT, result.model)
        assertTrue(abs(result.slopePerMin - 1_200.0) <= 50.0, "slope ${result.slopePerMin} ms/min")
    }

    @Test
    fun aRepeatedChorusHeardOnTimeIsVerifiedAndNotMovedOntoItsRepeat() {
        // Review 11-06: the same words every 8.1 s, heard on time ±30 ms. The
        // true hearings split across the bins either side of zero, the repeats
        // do not, and the sheet moved -8 s onto the previous chorus.
        val lines = List(12) { index -> line(10_000L + index * 8_100L, "chorus") }
        val heard = lines.mapIndexed { index, line ->
            heard("chorus", line.timeMs + if (index % 2 == 0) 30L else -30L)
        }
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied)
        assertTrue(result.verified)
    }

    @Test
    fun aPartialTranscriptOfARepeatedLineDoesNotMoveTheSheetTenSeconds() {
        // 11-06 n_cB5Yb-4Ro: lines=11 median=-10000ms model=wide applied=y from
        // a part of the song, then lines=30 median=5ms model=const once the
        // whole of it was heard: ten seconds wrong in between. Only the repeated
        // line was transcribed, on time, and the rest of the sheet not at all.
        val chorus = List(11) { index -> line(20_000L + index * 10_100L, "chorus") }
        val rest = List(19) { index -> line(131_100L + index * 5_000L, uniqueText(index)) }
        val heard = chorus.mapIndexed { index, line ->
            heard("chorus", line.timeMs + if (index % 2 == 0) 30L else -30L)
        }
        val result = RefinePolicy.compute(chorus + rest, heard)

        assertFalse(result.applied, "moved ${result.medianMs} ms by ${result.model}")
        assertTrue(abs(result.medianMs) < RefinePolicy.MIN_SHIFT_MS, "median ${result.medianMs} ms")
    }

    private fun misheardOpenings(lateMs: Long): Pair<List<LyricLine>, List<LyricsWordAlign.Heard>> {
        val lines = List(12) { index -> line(20_000L + index * 12_000L, uniqueText(index)) }
        val heard = lines.mapIndexed { index, line ->
            heard(uniqueText(index + 100).take(2) + line.text.drop(2), line.timeMs + lateMs)
        }
        return lines to heard
    }

    @Test
    fun aLateSheetWhoseOpeningsAreMisheardIsMoved() {
        // R4 reproduction: the narrow window sees nothing and the flat fit is under 1.5 s.
        val (lines, heard) = misheardOpenings(1_200L)
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
    }

    @Test
    fun anOnTimeSheetWhoseOpeningsAreMisheardIsNotMoved() {
        // A start placed on a line's third letter is stepped back further than
        // that letter was sung, here 260 ms early on every line. Taking that for
        // a flat move would put an on-time sheet 260 ms out.
        val (lines, heard) = misheardOpenings(0L)
        val result = RefinePolicy.compute(lines, heard)

        assertFalse(result.applied, "moved ${result.medianMs} ms by ${result.model}")
    }
}
