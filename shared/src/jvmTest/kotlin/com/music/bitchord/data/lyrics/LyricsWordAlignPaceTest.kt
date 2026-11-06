package com.music.bitchord.data.lyrics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsWordAlignPaceTest {

    /** Words with each letter taking [letterMs], from [startMs], one word after another. */
    private fun sung(words: List<String>, startMs: Long, letterMs: Long): List<LyricsWordAlign.Heard> {
        var at = startMs
        return words.map { word ->
            val end = at + word.length * letterMs
            LyricsWordAlign.Heard(word, at, end).also { at = end }
        }
    }

    @Test
    fun aLineWhoseFirstWordWasMisheardStepsBackAtItsOwnPace() {
        // Rap: 60 ms a letter. The opening letters are lost in the transcript, so
        // the start has to be stepped back from the first one that was heard.
        val line = "abcd efghijklmnop qrstuvwxyz"
        val heard = sung(listOf("efghijklmnop", "qrstuvwxyz"), startMs = 10_240L, letterMs = 60L)
        val start = LyricsWordAlign.placeLines(listOf(line), heard).single()!!
        assertTrue(abs(start - 10_000L) <= 30L, "stepped back to $start")
    }

    @Test
    fun aSlowLineStillStepsBackAtTheNominalPace() {
        val line = "abcd efghijklmnop"
        val heard = sung(listOf("efghijklmnop"), startMs = 10_720L, letterMs = 400L)
        val start = LyricsWordAlign.placeLines(listOf(line), heard).single()!!
        assertEquals(10_000L, start)
    }

    @Test
    fun aHeldNoteInsideTheLineDoesNotReadAsASlowLine() {
        // Sixty milliseconds a letter, with one word held for four seconds.
        val heard = listOf(
            LyricsWordAlign.Heard("efghij", 10_240L, 10_600L),
            LyricsWordAlign.Heard("klm", 10_600L, 14_600L),
            LyricsWordAlign.Heard("nopqrstuvw", 14_600L, 15_200L),
        )
        val start = LyricsWordAlign.placeLines(listOf("abcd efghij klm nopqrstuvw"), heard).single()!!
        assertTrue(abs(start - 10_000L) <= 30L, "stepped back to $start")
    }

    @Test
    fun aWholeFastSongIsStillAligned() {
        // 7,600 letters each way: past Rap God's own 6,300.
        val words = (0 until 1_000).map { "w${it}abc" }
        val lines = words.chunked(8).map { it.joinToString(" ") }
        val placement = LyricsWordAlign.placeLinesDetailed(lines, sung(words, 0L, 60L))
        assertTrue(placement.attempted, "not attempted")
        assertTrue(placement.starts.count { it != null } > lines.size * 9 / 10)
    }
}
