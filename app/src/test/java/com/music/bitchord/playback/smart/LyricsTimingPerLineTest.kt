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

    @Test
    fun `the song check agreement is out of the lines the transcript could spell`() {
        // NEON 11-06: 17 lines in the window, 8 on the fit, 5 of them opening in Hangul.
        val lines = (0 until 17).map { line(it * 2_500L, "line $it") }
        val indices = lines.indices.toList()
        val starts = indices.map { if (it < 8) lines[it].timeMs + 80L else null }

        assertEquals(47, LyricsTiming.fitAi(lines, indices, starts)?.matchPercent)
        assertEquals(67, LyricsTiming.fitAi(lines, indices, starts, spellable = 12)?.matchPercent)
        // Still under the 70% bar: a sheet this far off stays unapplied.
        assertEquals(false, com.music.bitchord.data.lyrics.LyricsAiGuard.agreementAllows(67))
        // Too few spellable lines is no base: back to all of them.
        assertEquals(47, LyricsTiming.fitAi(lines, indices, starts, spellable = 2)?.matchPercent)
    }
}
