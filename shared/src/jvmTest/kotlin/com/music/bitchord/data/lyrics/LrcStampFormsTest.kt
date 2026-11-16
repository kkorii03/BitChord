package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L notes: "[01:23]" and "[01:23.4]" rows were not lines at all (the sheet fell
 * to plain text with the stamps showing), a compressed row lost its second
 * time, and the word "error" anywhere dropped a whole unsynced sheet.
 */
class LrcStampFormsTest {

    @Test
    fun stampsWithoutOrWithOneFractionDigitAreLines() {
        val lines = LrcLib.parseLrc("[00:01]one\n[00:02.5]two\n[00:03.25]three")
        assertEquals(listOf(1_000L, 2_500L, 3_250L), lines.filterNot { it.isGap }.map { it.timeMs })
        assertEquals(listOf("one", "two", "three"), lines.filterNot { it.isGap }.map { it.text })
    }

    @Test
    fun aCompressedRowIsALineAtEachOfItsTimes() {
        val lines = LrcLib.parseLrc("[00:01.00]verse\n[00:02.00][00:04.00]chorus\n[00:03.00]bridge")
            .filterNot { it.isGap }
        assertEquals(listOf("verse", "chorus", "bridge", "chorus"), lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), lines.map { it.timeMs })
    }

    @Test
    fun aFullUnsyncedSheetMentioningErrorIsKept() {
        val sheet = "first line\nsecond line\nit was trial and error\nlast line"
        val lines = ProviderLyrics.parse(sheet)
        assertEquals(4, lines?.size)
    }

    @Test
    fun anErrorAnswerIsStillNoLyrics() {
        assertEquals(null, ProviderLyrics.parse("Error: lyrics not found"))
        assertEquals(null, ProviderLyrics.parse("Internal error\nplease retry later\nsorry"))
    }

    @Test
    fun theOldStampFormStillParses() {
        val lines = LrcLib.parseLrc("[00:10.123]a\n[00:11.12]b")
        assertTrue(lines.any { it.timeMs == 10_123L && it.text == "a" })
        assertTrue(lines.any { it.timeMs == 11_120L && it.text == "b" })
    }
}
