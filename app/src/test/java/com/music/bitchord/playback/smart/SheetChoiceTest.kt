package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
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

    private fun timedWord(line: LyricLine) = line.copy(words = listOf(LyricWord(line.timeMs, line.timeMs + 1_000L, line.text)))

    @Test
    fun `a word-timed sheet that also fits takes over from a line-timed one`() {
        val lineTimed = sheet(0)
        val wordTimed = lineTimed.map(::timedWord)
        assertEquals(wordTimed, pick(lineTimed, wordTimed, sung(lineTimed)))
    }

    @Test
    fun `a word-timed sheet that does not fit leaves a line-timed one alone`() {
        val lineTimed = sheet(0)
        assertNull(pick(lineTimed, sheet(24).map(::timedWord), sung(lineTimed)))
    }

    @Test
    fun `a word-timed sheet that fits is not replaced by another`() {
        val wordTimed = sheet(0).map(::timedWord)
        assertNull(pick(wordTimed, sheet(0).map { timedWord(it).copy(timeMs = it.timeMs + 300L) }, sung(wordTimed)))
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

    /**
     * w8P-BIQU1qI: the app measured the sheet on screen at 98% against 211 words;
     * a later listen kept 158, fit it 72% and replaced it, and the log flipped
     * between two others six times. The shorter listen must not judge it worse.
     */
    @Test
    fun `a sheet the app measured as fitting stays when the words in hand fit it worse`() {
        val onScreen = sheet(0).map(::timedWord)
        val heard = sung(sheet(24))
        assertNull(
            SheetChoice.better(
                onScreen,
                listOf(LyricsRepository.Result(LyricsSource.VIBE, sheet(24))),
                heard,
                knownFit = 98,
            ),
        )
    }

    /** w8P-BIQU1qI: 98% against 211 words, 72% against the 158 a later listen kept. */
    @Test
    fun `the app's own measure below the floor does not freeze the sheet`() {
        val onScreen = sheet(0).map(::timedWord)
        val heard = sung(sheet(24))
        assertEquals(
            sheet(24),
            SheetChoice.better(
                onScreen,
                listOf(LyricsRepository.Result(LyricsSource.VIBE, sheet(24))),
                heard,
                knownFit = 72,
            )?.lines,
        )
    }
}
