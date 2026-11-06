package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsWordAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words are made up: distinct letters so a line is either heard or it is not. */
class SheetChoiceTest {

    private val words = listOf(
        "amber", "birch", "cedar", "dune", "ember", "frost", "glade", "hazel", "ivory", "juniper", "kelp", "lotus",
        "maple", "nectar", "olive", "pebble", "quartz", "reed", "sage", "thistle", "umber", "violet", "willow", "xenon",
        "yarrow", "zephyr", "acorn", "bloom", "clover", "dahlia", "elm", "fern", "ginger", "heath", "iris", "jasmine",
        "kale", "laurel", "moss", "nettle", "oak", "poppy", "quince", "rowan", "spruce", "tansy", "ulex", "vetch",
    )

    private fun sheet(from: Int, lines: Int = 16): List<LyricLine> = List(lines) { i ->
        val at = from + i * 3
        LyricLine(5_000L + i * 4_000L, (0 until 3).joinToString(" ") { words[(at + it) % words.size] })
    }

    private fun sung(lines: List<LyricLine>, shiftMs: Long = 0L, onlyLines: Int = lines.size) =
        lines.take(onlyLines).flatMap { line ->
            line.text.split(' ').mapIndexed { i, word ->
                val at = line.timeMs + shiftMs + i * 500L
                LyricsWordAlign.Heard(word, at, at + 400L)
            }
        }

    private fun pick(current: List<LyricLine>, candidate: List<LyricLine>, heard: List<LyricsWordAlign.Heard>) =
        SheetChoice.better(current, listOf(LyricsRepository.Result(LyricsSource.VIBE, candidate)), heard)?.lines

    @Test
    fun `a sheet the words fit replaces one they do not`() {
        val right = sheet(0)
        val wrong = sheet(24)
        assertEquals(right, pick(wrong, right, sung(right)))
    }

    @Test
    fun `its own timing does not count against it, only whether the lines lie on one line`() {
        val right = sheet(0)
        val wrong = sheet(24)
        assertEquals(right, pick(wrong, right, sung(right, shiftMs = 1_200L)))
    }

    @Test
    fun `a sheet that fits is left alone`() {
        val right = sheet(0)
        val other = sheet(24)
        assertNull(pick(right, other, sung(right)))
    }

    @Test
    fun `two sheets of the same song stay as they are`() {
        val right = sheet(0)
        assertNull(pick(right, right.map { it.copy(timeMs = it.timeMs + 300L) }, sung(right)))
    }

    @Test
    fun `a candidate below the floor any timing has to clear is not taken`() {
        val heard = sung(sheet(0), onlyLines = 16)
        val third = sheet(0).mapIndexed { i, line -> if (i % 3 == 0) line else sheet(24)[i] }
        assertNull(pick(sheet(24), third, heard))
    }

    @Test
    fun `too few words heard change nothing`() {
        val right = sheet(0)
        assertNull(pick(sheet(24), right, sung(right, onlyLines = 4)))
    }

    @Test
    fun `no candidates, no change`() {
        assertNull(SheetChoice.better(sheet(24), emptyList(), sung(sheet(0))))
    }
}
