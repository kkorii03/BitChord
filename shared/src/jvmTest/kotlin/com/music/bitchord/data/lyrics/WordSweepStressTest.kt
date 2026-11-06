package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The sweep on the two shapes that break it: very fast rap and one note held for seconds. */
class WordSweepStressTest {

    private fun assertSweepHolds(line: LyricLine, until: Long) {
        val first = line.words.first().startMs
        var previous = 0f
        var at = line.timeMs - 500L
        while (at <= until) {
            val revealed = line.revealedChars(at)
            assertTrue(revealed >= previous, "went backwards at $at: $revealed < $previous")
            assertTrue(revealed <= line.text.length, "past the text at $at")
            if (at <= first) assertEquals(0f, revealed, "lit before the first word at $at")
            previous = revealed
            at += 5
        }
        assertEquals(line.text.length.toFloat(), line.revealedChars(until), "never fully lit")
    }

    /** 40 words in 5.2 s. */
    private val rapTokens = (1..40).map { "w${it}x" }
    private val rapText = rapTokens.joinToString(" ")

    private fun rapHeard(at: Long, drop: (Int) -> Boolean = { false }) = rapTokens.mapIndexedNotNull { i, token ->
        if (drop(i)) null else LyricsWordAlign.Heard(token, at + i * 130L, at + i * 130L + 110L)
    }

    @Test
    fun `rap on the sheet's own words stays in order and ends on its last word`() {
        val at = 10_000L
        val words = rapHeard(at).map { LyricWord(it.startMs, it.endMs, it.text) }
        assertSweepHolds(LyricLine(timeMs = at, text = rapText, words = words), words.last().endMs)
    }

    @Test
    fun `rap the transcript half missed keeps the heard words on their times`() {
        val at = 10_000L
        val next = at + 5_300L
        val heard = rapHeard(at) { it % 3 == 2 }
        val line = assertNotNull(LyricLine(timeMs = at, text = rapText).withHeardWords(heard, next))

        assertEquals(rapTokens, line.words.map { it.text })
        heard.forEach { word ->
            val placed = line.words.first { it.text == word.text }
            assertEquals(word.startMs, placed.startMs, word.text)
        }
        line.words.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs, "${b.text} overlaps ${a.text}") }
        assertTrue(line.words.last().endMs <= next)
        assertSweepHolds(line, next)
    }

    @Test
    fun `rap running past the next stamp is squeezed into its own slot, not lost`() {
        val at = 10_000L
        val next = at + 3_500L
        val line = assertNotNull(LyricLine(timeMs = at, text = rapText).withHeardWords(rapHeard(at), next))

        assertTrue(line.words.all { it.endMs <= next }, "a word ran into the next line")
        assertSweepHolds(line, next)
    }

    @Test
    fun `a note held on the sheet lights across the whole of it`() {
        val line = LyricLine(
            timeMs = 10_000L,
            text = "stay",
            words = listOf(LyricWord(10_000L, 16_000L, "stay")),
        )

        assertEquals(2f, line.revealedChars(13_000L), 0.01f)
        assertTrue(line.revealedChars(15_900L) < 4f, "lit before the note ended")
        assertSweepHolds(line, 16_000L)
    }

    /** Whisper dates the start of a held note and stops the word early (10 songs against hand times: -811 ms). */
    @Test
    fun `a note the transcript clipped still lights to the end of its slot`() {
        val line = assertNotNull(
            LyricLine(timeMs = 10_000L, text = "hold on").withHeardWords(
                listOf(LyricsWordAlign.Heard("hold", 10_000L, 10_300L), LyricsWordAlign.Heard("on", 10_400L, 10_800L)),
                nextLineMs = 16_300L,
            ),
        )

        assertEquals(10_400L, line.words.last().startMs)
        assertEquals(16_000L, line.words.last().endMs)
        assertEquals(10_300L, line.words.first().endMs, "only the last word is stretched")
        assertTrue(line.revealedChars(15_000L) < line.text.length, "fully lit with the note still going")
        assertSweepHolds(line, 16_000L)
    }

    @Test
    fun `a slot too long to be one line keeps the heard end`() {
        val line = assertNotNull(
            LyricLine(timeMs = 10_000L, text = "hold on").withHeardWords(
                listOf(LyricsWordAlign.Heard("hold", 10_000L, 10_300L), LyricsWordAlign.Heard("on", 10_400L, 10_800L)),
                nextLineMs = 30_000L,
            ),
        )

        assertEquals(10_800L, line.words.last().endMs)
    }

    @Test
    fun `a stated end is the end`() {
        val line = assertNotNull(
            LyricLine(timeMs = 10_000L, text = "hold on", sungUntilMs = 13_000L).withHeardWords(
                listOf(LyricsWordAlign.Heard("hold", 10_000L, 10_300L), LyricsWordAlign.Heard("on", 10_400L, 10_800L)),
                nextLineMs = 16_300L,
            ),
        )

        assertEquals(13_000L, line.words.last().endMs)
    }

    /** A backing vocal stamps the line; the lead's own first word is what lights. */
    @Test
    fun `nothing lights between the stamp and the first word`() {
        val line = LyricLine(
            timeMs = 10_000L,
            text = "lead words",
            words = listOf(LyricWord(10_884L, 11_200L, "lead"), LyricWord(11_300L, 11_700L, "words")),
        )

        assertEquals(0f, line.revealedChars(10_500L))
        assertTrue(line.revealedChars(11_000L) > 0f)
    }

    @Test
    fun `a filled last word is not a held note`() {
        val line = assertNotNull(
            LyricLine(timeMs = 10_000L, text = "hold on").withHeardWords(
                listOf(LyricsWordAlign.Heard("hold", 10_000L, 10_300L), LyricsWordAlign.Heard("on", 10_400L, 10_600L)),
                nextLineMs = 16_300L,
            ),
        )

        assertEquals(16_000L, line.words.last().endMs, "the sweep still fills the slot")
        assertTrue(line.growingWords.isEmpty(), "a word heard for 200 ms is not a held note")
        assertTrue(line.wordLift(1, 11_000L) <= 0.3f, "and it does not rise like one")
    }

    @Test
    fun `a word heard held still grows after the fill`() {
        val line = assertNotNull(
            LyricLine(timeMs = 10_000L, text = "hold on").withHeardWords(
                listOf(LyricsWordAlign.Heard("hold", 10_000L, 10_300L), LyricsWordAlign.Heard("on", 10_400L, 12_000L)),
                nextLineMs = 16_300L,
            ),
        )

        assertEquals(1, line.growingWords.size)
    }
}
