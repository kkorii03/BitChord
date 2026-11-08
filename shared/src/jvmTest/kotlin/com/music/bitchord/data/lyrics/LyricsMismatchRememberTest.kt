package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sheet rejected by more than one judge pass is still one wrong sheet. The
 * screen's judge and a refresh can both remember it; counting it twice would
 * make the *sound* the culprit after a single provider's sheet (11-08 KChyOsjqmfM).
 */
class LyricsMismatchRememberTest {

    private val verdict = LyricsMismatch.Verdict(heardWords = 120, matchedLines = 1, totalLines = 24, rejected = true)

    private fun sheet(text: String) = listOf(LyricLine(1_000L, text), LyricLine(6_000L, "$text 999"))

    @Test
    fun theSameSheetRememberedTwiceIsOneWrongSheet() {
        val id = "dup-sheet-1108"
        val lines = sheet("101 102 103")
        LyricsMismatch.rememberRejected(id, LyricsSource.LRCLIB, lines, verdict)
        LyricsMismatch.rememberRejected(id, LyricsSource.LRCLIB, lines, verdict)

        assertTrue(LyricsMismatch.isRejected(id, LyricsSource.LRCLIB, lines))
        assertNull(LyricsMismatch.wrongSound(id))
    }

    @Test
    fun twoDifferentSheetsStillMakeTheSoundTheCulprit() {
        val id = "two-sheets-1108"
        LyricsMismatch.rememberRejected(id, LyricsSource.LRCLIB, sheet("101 102 103"), verdict)
        LyricsMismatch.rememberRejected(id, LyricsSource.KUGOU, sheet("201 202 203"), verdict)

        assertNotNull(LyricsMismatch.wrongSound(id))
    }
}
