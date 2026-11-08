package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Gemini transcript sheet carries the sung words as its text, so the heard words place its
 * lines even when the transcript runs a whole line together (one heard "word"), which the
 * two-word anchor rule cannot use.
 */
class LyricsPerLineOwnTranscriptTest {

    private val names = "alpha bravo charlie delta echoes foxtrot golfing hotels indigo juliet kilos lima".split(" ")

    private val sheet = List(12) { index ->
        LyricLine(
            20_000L + index * 12_000L,
            "${names[index]}x ${names[(index + 1) % 12]}y ${names[(index + 2) % 12]}z ${names[(index + 3) % 12]}w",
        )
    }

    /** Each line heard as one run-together word [lateMs] after its stamp (the stamps of this sheet are early). */
    private fun heardRunTogether(lateMs: Long) = sheet.map { line ->
        val start = line.timeMs + lateMs
        LyricsWordAlign.Heard(line.text.replace(" ", ""), start, start + 3_000L)
    }

    @Test
    fun `without the flag a run-together line has no anchor and does not move`() {
        val layer = LyricsPerLine.compute(sheet, heardRunTogether(400L), heardAudioKey = 1, currentAudioKey = 1)

        assertEquals(0, layer.anchors)
        assertTrue(layer.shiftsMs.all { it == 0L })
    }

    @Test
    fun `a transcript sheet is placed by where its opening was heard`() {
        val layer = LyricsPerLine.compute(
            sheet, heardRunTogether(400L), heardAudioKey = 1, currentAudioKey = 1, ownTranscript = true,
        )

        assertEquals(12, layer.anchors)
        layer.shiftsMs.forEach { assertTrue(kotlin.math.abs(it - 400L) <= 60L, "moved $it ms, wanted about 400") }
    }

    @Test
    fun `a line heard more than the cap away is left where it is`() {
        val heard = heardRunTogether(400L).mapIndexed { index, word ->
            if (index == 5) word.copy(startMs = word.startMs + 3_000L, endMs = word.endMs + 3_000L) else word
        }

        val layer = LyricsPerLine.compute(
            sheet, heard, heardAudioKey = 1, currentAudioKey = 1, ownTranscript = true,
        )

        assertTrue(layer.shiftsMs[5] <= LyricsPerLine.MAX_SHIFT_TWO_WORDS_MS, "line 5 moved ${layer.shiftsMs[5]} ms")
    }

    @Test
    fun `lines the transcript did not sing are not placed`() {
        val layer = LyricsPerLine.compute(
            sheet, emptyList(), heardAudioKey = 1, currentAudioKey = 1, ownTranscript = true,
        )

        assertEquals(0, layer.anchors)
    }
}
