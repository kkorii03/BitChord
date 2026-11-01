package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What counts against a sheet: only the part of the song that was heard,
 * never an untimed sheet, and never a long comparison that is simply skipped.
 */
class LyricsMismatchCoverageTest {

    private fun heard(text: String, at: Long) = LyricsWordAlign.Heard(text, at, at + 300)

    private fun sung(lines: List<String>, count: Int): List<LyricsWordAlign.Heard> {
        val words = mutableListOf<LyricsWordAlign.Heard>()
        var at = 1_000L
        for (index in 0 until count) {
            lines[index].split(' ').forEach { word ->
                words += heard(word, at)
                at += 400L
            }
        }
        repeat(40) {
            words += heard("zzzz", at)
            at += 400L
        }
        return words
    }

    private val correct = (0 until 300).map { index ->
        "alpha${index} bravo${index} charlie${index} delta${index} echo${index}"
    }

    @Test
    fun aWrongSheetTooLongToAlignIsStillRejected() {
        val heard = sung(correct, 60)
        val wrong = (0 until 300).map { index ->
            "xyzzx${index} jkwjf${index} qqzzq${index} wxyzw${index} fjjzf${index}"
        }

        val verdict = LyricsMismatch.judge(wrong, heard)

        assertEquals(heard.size, verdict.heardWords)
        assertTrue(verdict.totalLines in 1 until wrong.size)
        assertTrue(verdict.matchedLines <= 1)
        assertTrue(verdict.rejected)
    }

    @Test
    fun aLongSheetHeardAtTheStartIsKept() {
        val heard = sung(correct, 60)

        val verdict = LyricsMismatch.judge(correct, heard)

        assertTrue(verdict.totalLines in 1 until correct.size)
        assertTrue(verdict.matchedLines > 0)
        assertFalse(verdict.rejected)
    }

    @Test
    fun anUntimedSheetIsNotJudgedAgainstTheSongCheck() {
        val lines = (0 until 40).map { index ->
            LyricLine(
                timeMs = 0L,
                text = "alpha${index} bravo${index} charlie${index} delta${index} echo${index}",
            )
        }
        // The song check heard only the first five lines; the rest of a
        // correct untimed sheet has no times to show it lies outside them.
        val words = sung(lines.map { it.text }, 5)

        val verdict = LyricsMismatch.judgeSheet(lines, words)

        assertEquals(40, verdict.totalLines)
        assertEquals(0, verdict.matchedLines)
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
