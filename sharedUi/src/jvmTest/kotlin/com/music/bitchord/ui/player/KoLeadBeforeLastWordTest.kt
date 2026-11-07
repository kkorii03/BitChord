package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ko.ui.player.koLeadBeforeLastWord
import kotlin.test.Test
import kotlin.test.assertEquals

class KoLeadBeforeLastWordTest {
    @Test
    fun noGapMeansThePanelWaitsForTheLastWord() {
        // Fast rap: the next line starts the moment this one's last word ends.
        val lines = listOf(
            LyricLine(1_000, "a", sungUntilMs = 2_000),
            LyricLine(2_000, "b", sungUntilMs = 3_000),
        )
        assertEquals(0L, koLeadBeforeLastWord(lines, 0, 350L))
    }

    @Test
    fun shortGapCapsTheLead() {
        val lines = listOf(
            LyricLine(1_000, "a", sungUntilMs = 2_000),
            LyricLine(2_120, "b", sungUntilMs = 3_000),
        )
        assertEquals(120L, koLeadBeforeLastWord(lines, 0, 350L))
    }

    @Test
    fun overlappingLinesNeverLeadNegative() {
        val lines = listOf(
            LyricLine(1_000, "a", sungUntilMs = 2_300),
            LyricLine(2_000, "b", sungUntilMs = 3_000),
        )
        assertEquals(0L, koLeadBeforeLastWord(lines, 0, 350L))
    }

    @Test
    fun aRealPauseKeepsTheFullLead() {
        val lines = listOf(
            LyricLine(1_000, "a", sungUntilMs = 2_000),
            LyricLine(3_000, "b", sungUntilMs = 4_000),
        )
        assertEquals(500L, koLeadBeforeLastWord(lines, 0, 500L))
    }

    @Test
    fun lineTimedSheetsKeepTheLead() {
        // No known end: the line's "end" is its start, which says nothing.
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(1_200, "b"))
        assertEquals(350L, koLeadBeforeLastWord(lines, 0, 350L))
    }

    @Test
    fun beforeTheFirstLineAndOnTheLastLineKeepTheLead() {
        val lines = listOf(LyricLine(1_000, "a", sungUntilMs = 2_000))
        assertEquals(350L, koLeadBeforeLastWord(lines, -1, 350L))
        assertEquals(350L, koLeadBeforeLastWord(lines, 0, 350L))
    }
}
