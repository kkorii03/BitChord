package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The per-line residual layer: only lines the heard words clearly place elsewhere move. */
class LyricsPerLineTest {

    private fun line(at: Long, text: String) = LyricLine(timeMs = at, text = text)

    private fun heardLine(line: LyricLine, residualMs: Long): List<LyricsWordAlign.Heard> {
        var position = 0
        return line.text.split(" ").map { text ->
            val start = line.timeMs + residualMs + position * 180L
            position += LyricsWordAlign.fold(text).length
            LyricsWordAlign.Heard(text, start, start + 200L)
        }
    }

    private fun layer(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>) =
        LyricsPerLine.compute(lines, heard, heardAudioKey = 1, currentAudioKey = 1)

    @Test
    fun `a constant scatter is removed line by line`() {
        val lines = listOf(
            line(10_000, "alpha bravo"),
            line(20_000, "charlie delta"),
            line(30_000, "echo foxtrot"),
            line(40_000, "golf hotel"),
        )
        val heard = lines.flatMap { heardLine(it, 500L) }
        val result = layer(lines, heard)

        assertEquals(4, result.anchors)
        assertEquals(listOf(500L, 500L, 500L, 500L), result.shiftsMs)
    }

    @Test
    fun `a single ten second outlier is moved when three words match`() {
        val lines = listOf(
            line(10_000, "alpha bravo"),
            line(20_000, "charlie delta echo"),
            line(30_000, "foxtrot golf"),
        )
        val heard = buildList {
            addAll(heardLine(lines[0], 0L))
            addAll(heardLine(lines[1], 10_000L))
            addAll(heardLine(lines[2], 0L))
        }
        val result = layer(lines, heard)

        assertEquals(3, result.anchors)
        assertEquals(0L, result.shiftsMs[0])
        assertEquals(LyricsPerLine.MAX_SHIFT_MANY_WORDS_MS, result.shiftsMs[1])
        assertEquals(0L, result.shiftsMs[2])
    }

    @Test
    fun `a two word outlier moves no further than its cap`() {
        val lines = listOf(
            line(10_000, "alpha bravo"),
            line(20_000, "charlie delta"),
        )
        val heard = heardLine(lines[0], 0L) + heardLine(lines[1], 2_000L)
        val result = layer(lines, heard)

        assertEquals(2, result.anchors)
        assertEquals(0L, result.shiftsMs[0])
        assertEquals(LyricsPerLine.MAX_SHIFT_TWO_WORDS_MS, result.shiftsMs[1])
    }

    @Test
    fun `lines are never reordered`() {
        val lines = listOf(
            line(10_000, "alpha bravo"),
            line(10_100, "charlie delta"),
        )
        val heard = heardLine(lines[0], 400L) + heardLine(lines[1], 0L)
        val result = layer(lines, heard)

        assertEquals(400L, result.shiftsMs[0])
        assertEquals(350L, result.shiftsMs[1])
        val starts = lines.indices.map { lines[it].timeMs + result.shiftsMs[it] }
        assertTrue(starts[1] >= starts[0] + LyricsPerLine.MIN_LINE_GAP_MS)
    }

    @Test
    fun `lines between two agreeing anchors interpolate`() {
        val lines = listOf(
            line(10_000, "alpha bravo"),
            line(20_000, "middle words"),
            line(30_000, "charlie delta"),
        )
        val heard = heardLine(lines[0], 100L) + heardLine(lines[2], 300L)
        val result = layer(lines, heard)

        assertEquals(2, result.anchors)
        assertEquals(listOf(100L, 200L, 300L), result.shiftsMs)
    }

    @Test
    fun `a line outside the heard range is untouched`() {
        val lines = listOf(
            line(10_000, "alpha bravo"),
            line(100_000, "charlie delta"),
        )
        val heard = heardLine(lines[0], 0L)
        val result = layer(lines, heard)

        assertEquals(0L, result.shiftsMs[0])
        assertEquals(0L, result.shiftsMs[1])
    }

    @Test
    fun `words heard on another rendition are skipped`() {
        val lines = listOf(line(10_000, "alpha bravo"))
        val heard = heardLine(lines[0], 500L)
        val result = LyricsPerLine.compute(lines, heard, heardAudioKey = 7, currentAudioKey = 8)

        assertEquals(LyricsPerLine.Layer.identity(1), result)
    }

    @Test
    fun `no heard words means no change`() {
        val lines = listOf(line(10_000, "alpha bravo"))
        assertEquals(LyricsPerLine.Layer.identity(1), layer(lines, emptyList()))
    }
}
