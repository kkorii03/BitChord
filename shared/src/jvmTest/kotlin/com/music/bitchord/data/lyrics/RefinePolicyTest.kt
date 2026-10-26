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
        // 260 ms per minute over 63 s: a constant move leaves the last lines
        // further out than the first, so the slope is what the residuals say.
        val startMs = 10_000L
        val stepMs = 7_000L
        val slopePerMs = 260.0 / 60_000.0
        val lines = List(10) { index -> line(startMs + index * stepMs, "line$index") }
        val heard = lines.map { line ->
            heard(line.text, line.timeMs + (slopePerMs * line.timeMs).toLong())
        }
        val result = RefinePolicy.compute(lines, heard)

        assertTrue(result.applied)
        assertTrue(abs(result.slopePerMin - 260.0) <= 26.0, "slope ${result.slopePerMin} ms/min")
        assertEquals(lines.size, result.lines)
    }
}
