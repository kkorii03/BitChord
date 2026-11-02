package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsWordAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-line layer is applied through [LyricsTiming.apply], after refine and under the hand. */
class LyricsTimingPerLineTest {

    private fun line(at: Long, text: String) = LyricLine(timeMs = at, text = text)

    private fun heardLine(line: LyricLine, residualMs: Long): List<LyricsWordAlign.Heard> {
        var position = 0
        return line.text.split(" ").map { text ->
            val start = line.timeMs + residualMs + position * 180L
            position += text.length
            LyricsWordAlign.Heard(text, start, start + 200L)
        }
    }

    @Test
    fun `the per line move sits between the refine and the hand`() {
        val lines = listOf(line(10_000L, "alpha bravo"))
        val timing = LyricsTiming.Timing(
            base = null,
            manualMs = 120L,
            perLine = LyricsTiming.PerLineWords(heardLine(lines[0], 500L), wordsKey = 1, currentKey = 1),
        )

        assertEquals(10_620L, LyricsTiming.apply(lines, timing)[0].timeMs)
    }

    @Test
    fun `words heard on another rendition do not move the lines`() {
        val lines = listOf(line(10_000L, "alpha bravo"))
        val timing = LyricsTiming.Timing(
            base = null,
            manualMs = 120L,
            perLine = LyricsTiming.PerLineWords(heardLine(lines[0], 500L), wordsKey = 1, currentKey = 2),
        )

        assertEquals(10_120L, LyricsTiming.apply(lines, timing)[0].timeMs)
    }
}
