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
            if (index == 5) word.copy(startMs = word.startMs + 5_000L, endMs = word.endMs + 5_000L) else word
        }

        val layer = LyricsPerLine.compute(
            sheet, heard, heardAudioKey = 1, currentAudioKey = 1, ownTranscript = true,
        )

        assertTrue(layer.shiftsMs[5] <= LyricsPerLine.MAX_SHIFT_TWO_WORDS_MS, "line 5 moved ${layer.shiftsMs[5]} ms")
    }

    /**
     * Gemini stamps lines one by one: 11-08 n3 4Jo__IP-Vdk had single lines and short runs
     * 1.2-3.0 s off between right ones. Each is placed where it was heard, not held to the
     * 600 ms two-word cap or dropped as a lone block because its neighbours are right.
     */
    @Test
    fun `single lines and a short run of a transcript sheet seconds off are each placed`() {
        val off = mapOf(3 to 2_500L, 7 to -1_800L, 8 to -2_300L)
        val heard = sheet.mapIndexed { index, line ->
            val start = line.timeMs + (off[index] ?: 0L)
            LyricsWordAlign.Heard(line.text.replace(" ", ""), start, start + 3_000L)
        }

        val layer = LyricsPerLine.compute(
            sheet, heard, heardAudioKey = 1, currentAudioKey = 1, ownTranscript = true,
        )

        sheet.indices.forEach { index ->
            val wanted = off[index] ?: 0L
            assertTrue(kotlin.math.abs(layer.shiftsMs[index] - wanted) <= 60L, "line $index moved ${layer.shiftsMs[index]} ms, wanted $wanted")
        }
    }

    @Test
    fun `a provider sheet keeps the lone-block guard for the same words`() {
        val off = mapOf(3 to 2_500L)
        val heard = sheet.flatMapIndexed { index, line ->
            // Words heard one by one, as for any sheet; the line opens 2.5 s late.
            val start = line.timeMs + (off[index] ?: 0L)
            line.text.split(" ").mapIndexed { w, word -> LyricsWordAlign.Heard(word, start + w * 700L, start + w * 700L + 500L) }
        }

        val layer = LyricsPerLine.compute(sheet, heard, heardAudioKey = 1, currentAudioKey = 1)

        assertTrue(kotlin.math.abs(layer.shiftsMs[3]) < 2_000L, "a provider line moved ${layer.shiftsMs[3]} ms")
    }

    @Test
    fun `lines the transcript did not sing are not placed`() {
        val layer = LyricsPerLine.compute(
            sheet, emptyList(), heardAudioKey = 1, currentAudioKey = 1, ownTranscript = true,
        )

        assertEquals(0, layer.anchors)
    }
}
