package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What counts against a sheet: only the part of the song that was heard, and
 * never a comparison that could not run at all.
 */
class LyricsMismatchCoverageTest {

    private fun heard(text: String, at: Long) = LyricsWordAlign.Heard(text, at, at + 300)

    @Test
    fun aSheetTooLongToAlignIsNotRejected() {
        val words = mutableListOf<LyricsWordAlign.Heard>()
        var at = 1_000L
        repeat(40) {
            words += heard("abcdefghij".repeat(10), at)
            at += 400L
        }
        val lines = (0 until 200).map { "abcdefghij".repeat(3) + it }

        val verdict = LyricsMismatch.judge(lines, words)

        assertEquals(40, verdict.heardWords)
        assertFalse(verdict.rejected)
    }

    @Test
    fun aSongCheckWindowDoesNotCountTheRestOfALongSong() {
        val lines = (0 until 20).map { index ->
            LyricLine(
                timeMs = 5_000L + index * 10_000L,
                text = "line${index}alpha line${index}bravo line${index}charlie",
            )
        }
        val words = mutableListOf<LyricsWordAlign.Heard>()
        var at = 1_000L
        lines[0].text.split(' ').forEach { word ->
            words += heard(word, at)
            at += 400L
        }
        repeat(40) {
            words += heard("zzzz", at)
            at += 400L
        }

        val verdict = LyricsMismatch.judgeSheet(lines, words)

        // Only the two lines inside the heard span were judged, and one of
        // them was actually sung: counting all twenty would reject it.
        assertEquals(2, verdict.totalLines)
        assertTrue(verdict.matchedLines >= 1)
        assertFalse(verdict.rejected)
    }
}
