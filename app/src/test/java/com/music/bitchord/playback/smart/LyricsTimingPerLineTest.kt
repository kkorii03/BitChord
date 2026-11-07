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

    @Test
    fun `many placed lines on one straight line are judged on themselves`() {
        // jDrP5Pb2f_o 11-06: 38 lines in the window, 26 placed, every one of them on the fit.
        val lines = (0 until 38).map { line(it * 2_500L, "line $it") }
        val indices = lines.indices.toList()
        val agreeing = indices.map { if (it < 26) lines[it].timeMs + 540L else null }
        assertEquals(100, LyricsTiming.fitAi(lines, indices, agreeing, spellable = 38)?.matchPercent)

        // Placed lines that scatter keep the sheet's base: half on, half far off.
        val split = indices.map {
            when {
                it >= 26 -> null
                it % 2 == 0 -> lines[it].timeMs + 540L
                else -> lines[it].timeMs + 20_000L + it * 700L
            }
        }
        val scattered = LyricsTiming.fitAi(lines, indices, split, spellable = 38)?.matchPercent
        assertEquals(false, scattered != null && com.music.bitchord.data.lyrics.LyricsAiGuard.agreementAllows(scattered))
    }

    // ---- A transcript's stretches against the straight line ----------------------------------

    private val sheet = (0 until 40).map { line(10_000L + it * 4_000L, "line number $it") }
    private val all = sheet.indices.toList()

    // 20 lines on +7 s and 20 on +40 s, too far apart for one line: half on it at best.
    private val twoClocks = sheet.mapIndexed { i, it -> it.timeMs + if (i < 20) 7_000L else 40_000L }

    private fun placement(starts: List<Long?>) = LyricsWordAlign.Placement(
        starts = starts,
        attempted = true,
        matchedShare = starts.map { if (it != null) 1.0 else 0.0 },
    )

    @Test
    fun `a song the straight line fits comes back on the straight line`() {
        val starts = sheet.map { it.timeMs + 5_000L }
        val line = LyricsTiming.fitAi(sheet, all, starts)
        val fitted = LyricsTiming.fitHeard("v", sheet, all, placement(starts), Long.MAX_VALUE) { _, _ -> true }
        assertEquals(line, fitted)
    }

    @Test
    fun `a refused line gives way to the stretches and each line keeps its own clock`() {
        val fitted = LyricsTiming.fitHeard("v", sheet, all, placement(twoClocks), Long.MAX_VALUE) { fit, _ ->
            com.music.bitchord.data.lyrics.LyricsAiGuard.agreementAllows(fit.matchPercent)
        }
        assertEquals(100, fitted?.matchPercent)
        assertEquals(sheet[3].timeMs + 7_000L, fitted?.map?.map(sheet[3].timeMs))
        assertEquals(sheet[33].timeMs + 40_000L, fitted?.map?.map(sheet[33].timeMs))
    }

    @Test
    fun `when nothing may apply the straight line goes back for the caller to refuse`() {
        val line = LyricsTiming.fitAi(sheet, all, twoClocks)
        assertEquals(line, LyricsTiming.fitHeard("v", sheet, all, placement(twoClocks), Long.MAX_VALUE) { _, _ -> false })
    }

    @Test
    fun `stretches carry across a clock change in the order they were measured`() {
        val fitted = LyricsTiming.fitHeard("v", sheet, all, placement(twoClocks), Long.MAX_VALUE) { _, _ -> true }!!
        // On a verified copy two seconds behind the upload, after base and before refine.
        val timing = LyricsTiming.Timing(
            base = fitted,
            manualMs = 0L,
            hq = LyricsTiming.Adjustment.Mapped(
                com.music.bitchord.data.lyrics.LyricsTimeMap.linear(1.0, 2_000L),
                byAi = false,
                rate = 1.0,
                matchPercent = 90,
            ),
        )
        val shown = LyricsTiming.apply(sheet, timing).map { it.timeMs }
        assertEquals(sheet[3].timeMs + 9_000L, shown[3])
        assertEquals(sheet[33].timeMs + 42_000L, shown[33])
        assertEquals(shown.sorted(), shown)
    }

    @Test
    fun `stretches are held to their furthest move, not their first line`() {
        val moves = ArrayList<Long>()
        LyricsTiming.fitHeard("v", sheet, all, placement(twoClocks), Long.MAX_VALUE) { fit, moved ->
            moves += moved
            fit.map.knots == 1 && moved == 0L
        }
        // The straight line first, on its first line; then the stretches, on +40 s.
        assertEquals(40_000L, moves.last())
    }

    // ---- A refine that measured the sheet against the per-line layer --------------------------

    // 21 lines four seconds apart; five in the middle heard 1 184 ms late, the rest where they are
    // shown. A lone moved line is a stray the layer drops, so the block of five is what it moves for.
    private fun measuredSheet() = (0 until 21).map { line(it * 4_000L, "alpha$it bravo$it charlie$it") }

    private fun measuredWords(lines: List<LyricLine>) = lines.flatMapIndexed { index, sung ->
        heardLine(sung, if (index in 8..12) 1_184L else 0L)
    }

    @Test
    fun `a refine that already measured the sheet leaves the per-line layer nothing to add`() {
        // eqKhhG4FVoQ rs5/auto115 11-07: refine lines=21 median=84ms mad=43ms applied=y, then the
        // per-line layer moved a line up to 1 184 ms. The refine had already placed the sheet.
        val lines = measuredSheet()
        val words = measuredWords(lines)
        val settled = LyricsTiming.Timing(
            base = null,
            manualMs = 0L,
            perLine = LyricsTiming.PerLineWords(words, wordsKey = 1, currentKey = 1),
            refineMadMs = 43L,
        )

        assertEquals(
            "eqKhhG4FVoQ mad=43ms: the refine measured this sheet, line 10 keeps its ${lines[10].timeMs} ms",
            lines[10].timeMs,
            LyricsTiming.apply(lines, settled)[10].timeMs,
        )
        // Same words, same lines, no refine mad: the guard is the only difference.
        assertEquals(
            "eqKhhG4FVoQ with no refine mad: the per-line layer moves line 10 by 1 184 ms",
            lines[10].timeMs + 1_184L,
            LyricsTiming.apply(lines, settled.copy(refineMadMs = null))[10].timeMs,
        )
    }

    @Test
    fun `a scattered refine leaves the per-line layer its job`() {
        // 04tYkKUPPv4 rs5/fleet53 11-07: refine lines=31 median=220ms mad=370ms applied=n, the mad
        // over RefinePolicy.MAX_MAD_MS, so the per-line layer must still move what it heard.
        val lines = measuredSheet()
        val words = measuredWords(lines)
        val scattered = LyricsTiming.Timing(
            base = null,
            manualMs = 0L,
            perLine = LyricsTiming.PerLineWords(words, wordsKey = 1, currentKey = 1),
            refineMadMs = 370L,
        )

        assertEquals(
            "04tYkKUPPv4 mad=370ms: the per-line layer still moves line 10 by 1 184 ms",
            lines[10].timeMs + 1_184L,
            LyricsTiming.apply(lines, scattered)[10].timeMs,
        )
    }
}
