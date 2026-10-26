package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The last line-level correction: one constant move only when a whole song agrees. */
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
}
