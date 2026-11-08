package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** A word sheet on a whole-song fit moves a line only by a first-word snap every stricter test agrees with ([LyricsPerLine.wordSheetSnaps]). */
class LyricsPerLineWordSheetTest {

    private val first = "amber blaze cider dunes ember fable glint hazel irony jumbo karma lemon".split(" ")
    private val second = "north oasis pearl quilt raven sable tulip umber vivid waltz xenon yacht".split(" ")
    private val third = "zebra azure briar coral delta eagle flint grove heron ivory jewel knoll".split(" ")

    private fun sung(i: Int) = 20_000L + i * 4_000L

    /** Twelve three-word lines, word k sung 900 ms after word k-1; the sheet stamps line i [stamp] (i) ms off. */
    private fun sheet(
        stamp: (Int) -> Long,
        laterWords: (Int) -> Long = stamp,
        text: (Int) -> String = { "${first[it]} ${second[it]} ${third[it]}" },
    ) = List(12) { i ->
        val words = text(i).split(" ").mapIndexed { k, w ->
            val at = sung(i) + k * 900L + if (k == 0) stamp(i) else laterWords(i)
            LyricWord(at, at + 300L, w)
        }
        LyricLine(timeMs = words.first().startMs, text = text(i), words = words)
    }

    private fun heard(text: (Int) -> String = { "${first[it]} ${second[it]} ${third[it]}" }) =
        List(12) { i -> text(i).split(" ").mapIndexed { k, w -> LyricsWordAlign.Heard(w, sung(i) + k * 900L, sung(i) + k * 900L + 300L) } }
            .flatten()

    private fun layer(lines: List<LyricLine>, words: List<LyricsWordAlign.Heard>) =
        LyricsPerLine.wordSheetSnaps(lines, words, heardAudioKey = 1, currentAudioKey = 1)

    /** 11-09 OxgiiyLp5pk (KuGou) in figures: line stamps off by their own ±0.35 s, the words with them. */
    private val jitter = listOf(0L, 350L, -320L, 0L, 450L, -380L, 0L, 330L, 0L, -360L, 120L, -90L)

    @Test
    fun `a word sheet's stamps that scatter start on the heard first word`() {
        val result = layer(sheet({ jitter[it] }), heard())

        jitter.forEachIndexed { i, off ->
            val want = if (kotlin.math.abs(off) >= LyricsPerLine.MIN_WORD_SHEET_MOVE_MS) -off else 0L
            assertEquals(want, result.shiftsMs[i], "line $i stamped $off ms off")
        }
        assertEquals(6, result.snapped)
    }

    /** 6rS7OUGXUik line 37: the opening heard twice within seconds is no place to start the line. */
    @Test
    fun `a line whose opening is heard again nearby is left alone`() {
        // Line 3 ends on the word line 4 opens with, heard 2.2 s before it.
        val text = { i: Int -> if (i == 3) "${first[3]} ${second[3]} ${first[4]}" else "${first[i]} ${second[i]} ${third[i]}" }
        val result = layer(sheet({ jitter[it] }, text = text), heard(text))

        assertEquals(0L, result.shiftsMs[4])
        assertEquals(-350L, result.shiftsMs[1], "the other lines still move")
    }

    /** Only the first stamp slipped: the sheet's later words were heard where it has them. */
    @Test
    fun `a line whose later words agree with the sheet is not moved`() {
        val result = layer(sheet({ jitter[it] }, laterWords = { if (it == 4) 0L else jitter[it] }), heard())

        assertEquals(0L, result.shiftsMs[4])
        assertEquals(380L, result.shiftsMs[5])
    }

    @Test
    fun `a line the sheet repeats is not moved`() {
        val text = { i: Int -> if (i == 9) "${first[4]} ${second[4]} ${third[4]}" else "${first[i]} ${second[i]} ${third[i]}" }
        val result = layer(sheet({ jitter[it] }, text = text), heard(text))

        assertEquals(0L, result.shiftsMs[4])
        assertEquals(0L, result.shiftsMs[9])
        assertEquals(-350L, result.shiftsMs[1])
    }

    /** Past 0.8 s the opening is more likely another place than a tapping slip. */
    @Test
    fun `a move past the word-sheet cap is not made`() {
        val far = jitter.toMutableList().apply { this[4] = 950L; this[5] = -450L }
        val result = layer(sheet({ far[it] }), heard())

        assertEquals(0L, result.shiftsMs[4])
        assertEquals(450L, result.shiftsMs[5])
    }

    @Test
    fun `words heard on another rendition move nothing`() {
        val result = LyricsPerLine.wordSheetSnaps(sheet({ jitter[it] }), heard(), heardAudioKey = 1, currentAudioKey = 2)

        assertEquals(List(12) { 0L }, result.shiftsMs)
    }
}
