package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A provider's sheet against the words the sound actually sang. */
class LyricsMismatchTest {

    private val lines = listOf(
        "alpha bravo charlie delta echo",
        "foxtrot golf hotel india juliet",
        "kilo lima mike november oscar",
        "papa puppy romeo sierra tango",
        "uniform victor whiskey xray yankee",
        "amber birch cedar dune ember",
        "frost glade hazel ivory juniper",
        "kelp lotus maple nectar olive",
        "pebble reed sage thistle umber",
        "violet willow yarrow aspen bloom",
    )

    private fun heard(text: String, at: Long) = LyricsWordAlign.Heard(text, at, at + 300)

    private fun sound(vararg sungLines: Int): List<LyricsWordAlign.Heard> {
        val words = mutableListOf<LyricsWordAlign.Heard>()
        var at = 1_000L
        for (index in sungLines) {
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

    @Test
    fun aSheetTheSoundBarelySingsIsRejected() {
        val verdict = LyricsMismatch.judge(lines, sound(0))
        assertEquals(45, verdict.heardWords)
        assertTrue(verdict.matchedLines <= 1)
        assertEquals(10, verdict.totalLines)
        assertTrue(verdict.rejected)
    }

    @Test
    fun aSheetWithEnoughLinesHeardIsKept() {
        val verdict = LyricsMismatch.judge(lines, sound(0, 2, 4))
        assertEquals(3, verdict.matchedLines)
        assertEquals(10, verdict.totalLines)
        assertFalse(verdict.rejected)
    }

    @Test
    fun tooLittleSoundIsNoVerdict() {
        val words = lines[0].split(' ').mapIndexed { index, word -> heard(word, 1_000L + index * 400L) }
        val verdict = LyricsMismatch.judge(lines, words)
        assertEquals(5, verdict.heardWords)
        assertFalse(verdict.rejected)
        assertFalse(LyricsMismatch.judge(lines, emptyList()).rejected)
    }
}
