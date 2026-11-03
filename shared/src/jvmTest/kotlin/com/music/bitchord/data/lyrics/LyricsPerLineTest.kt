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

    /**
     * 11-03 user video, Grenade (Bruno Mars): Deepgram's words from the screen
     * recording (+10 s) against the line stamps the panel showed. English is sung
     * at about half the 180 ms a letter the offsets assume, so every line's words
     * scattered by seconds, no line was an anchor ('perline anchors=0'), and
     * "Take a bullet…" stayed ~1 s late, then rushed through its words.
     */
    @Test
    fun `english lines sung faster than the nominal letter pace still anchor`() {
        val lines = listOf(
            line(8_700, "I would go through all this pain"),
            line(14_300, "Take a bullet straight through my brain"),
            line(17_150, "Yes, I would die for you, baby"),
            line(20_700, "But you won't do the same"),
        )
        val heard = listOf(
            "through" to 10_160L, "all" to 10_640L, "this" to 11_120L, "pain" to 11_520L,
            "take" to 13_360L, "a" to 13_680L, "bullet" to 13_920L, "straight" to 14_400L,
            "through" to 14_880L, "my" to 15_440L, "brain" to 15_840L,
            "yes" to 17_200L, "i" to 17_520L, "went" to 17_680L, "down" to 18_000L,
            "for" to 18_480L, "your" to 18_800L, "baby" to 19_120L,
            "but" to 20_720L, "you" to 20_960L, "won't" to 21_200L, "do" to 21_600L,
            "the" to 21_840L, "same" to 22_080L,
        ).map { (text, start) -> LyricsWordAlign.Heard(text, start, start + 240L) }
        val result = layer(lines, heard)

        assertTrue(result.anchors >= 3, "anchors=${result.anchors}")
        assertEquals(-940L, result.shiftsMs[1])
    }

    @Test
    fun `no heard words means no change`() {
        val lines = listOf(line(10_000, "alpha bravo"))
        assertEquals(LyricsPerLine.Layer.identity(1), layer(lines, emptyList()))
    }
}
