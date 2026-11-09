package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 11-10 sbUKtlkRDvE (phone, cover video): the quick listen heard only the opening
 * and fitted -13.18 s at 100%, but the base choice placed those words against the
 * whole sheet. The sheet sings its verse twice; the alignment's free ends put the
 * opening on the second time, every base looked 80-95 s off, and the right
 * measurement (-13.09 s) was refused for "none (below threshold)".
 */
class BaseChoiceReachTest {

    private val verse = listOf(
        "amber river under morning",
        "bright candle over silent",
        "copper window facing north",
        "distant thunder rolling slow",
        "empty platform waiting long",
        "frozen garden losing petals",
        "golden harbor calling home",
        "hollow mountain keeping secrets",
    )

    /** The verse, then the same verse again a hundred seconds later. */
    private val lines = verse + verse
    private val sheetStarts = List(verse.size) { 20_000L + it * 8_000L } + List(verse.size) { 120_000L + it * 8_000L }

    /** The sheet is 13 s late: what is sung at sheet time t is heard at t - 13 s. */
    private val lateMs = 13_000L

    private fun heardVerse(fromMs: Long) = verse.flatMapIndexed { i, line ->
        line.split(' ').mapIndexed { w, word ->
            val start = fromMs + i * 8_000L + w * 600L
            LyricsWordAlign.Heard(word, start, start + 500L)
        }
    }

    /** An opening listen: only the first verse was heard. */
    private val opening = heardVerse(20_000L - lateMs)

    @Test
    fun wholeSheetPlacementPutsTheOpeningOnTheRepeat() {
        // The old call: no line starts, every line placed.
        val starts = BaseChoicePolicy.heardStarts(lines, opening)
        assertTrue(starts.take(verse.size).all { it == null }, "first verse unplaced: $starts")
        assertTrue(starts.drop(verse.size).all { it != null }, "repeat took the opening: $starts")
    }

    @Test
    fun openingWordsPlaceOnlyTheLinesTheyReach() {
        val starts = BaseChoicePolicy.heardStarts(lines, opening, sheetStarts)
        verse.indices.forEach { i ->
            assertEquals(sheetStarts[i] - lateMs, starts[i], "line $i")
            assertNull(starts[verse.size + i], "repeat line $i is past the words")
        }
    }

    @Test
    fun rightMeasurementIsKeptOnOpeningWords() {
        val heard = BaseChoicePolicy.heardStarts(lines, opening, sheetStarts)
        val choice = BaseChoicePolicy.choose(
            defaultName = BaseChoicePolicy.MEASURED,
            heardStarts = heard,
            candidates = listOf(
                BaseChoicePolicy.Candidate(BaseChoicePolicy.NONE, sheetStarts),
                BaseChoicePolicy.Candidate(BaseChoicePolicy.MEASURED, sheetStarts.map { it - lateMs }),
            ),
        )
        assertEquals(BaseChoicePolicy.MEASURED, choice.name, "scores: ${choice.scores}")
        val measured = choice.scores.first { it.first == BaseChoicePolicy.MEASURED }.second
        assertEquals(verse.size, measured.matched)
        assertEquals(0L, measured.medianAbsMs)
    }

    @Test
    fun wholeSongWordsPlaceAsBefore() {
        val whole = opening + heardVerse(120_000L - lateMs)
        assertEquals(
            BaseChoicePolicy.heardStarts(lines, whole),
            BaseChoicePolicy.heardStarts(lines, whole, sheetStarts),
        )
    }
}
