package com.music.bitchord.ko.ui.player

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.LyricsTranslationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review X3: a re-timed list (video-clock move, measurement, sync drawer) that
 * lands while the first translation is still loading. The effect used to be
 * keyed on the list alone, so it ran during Loading, did nothing, and never ran
 * again: the translation arrived built from the old list's times and the
 * translated strip kept the old clock for the rest of the song.
 */
class KoRetimeKeyTest {

    private val before = listOf(LyricLine(1_000, "a"), LyricLine(4_000, "b"))
    private val after = listOf(LyricLine(3_000, "a"), LyricLine(6_000, "b")) // same words, +2 s

    @Test
    fun `a translation finishing after a re-time re-runs the check and finds the old clock`() {
        val whileLoading = koRetimeKey(after, LyricsTranslationUiState.Loading)
        val builtFromOldList = LyricsTranslationUiState.Ready(before)
        val whenReady = koRetimeKey(after, builtFromOldList)

        assertNotEquals(whileLoading, whenReady)
        assertFalse(koSameTiming(builtFromOldList.lines, after))
    }

    @Test
    fun `once re-made for the moved lines the check settles`() {
        val remade = LyricsTranslationUiState.Ready(after)
        assertEquals(koRetimeKey(after, remade), koRetimeKey(after, LyricsTranslationUiState.Ready(after)))
        assertTrue(koSameTiming(remade.lines, after))
    }

    /** Review X4: auto-translate asks once per sheet, not once per re-timed list. */
    @Test
    fun `a re-timed list keeps the auto-translate key and new words change it`() {
        assertEquals(koLyricsWordsKey(before), koLyricsWordsKey(after))
        assertNotEquals(koLyricsWordsKey(before), koLyricsWordsKey(listOf(LyricLine(1_000, "c"))))
        assertNotEquals(koLyricsWordsKey(null), koLyricsWordsKey(before))
    }

    @Test
    fun `states other than Ready do not re-run it`() {
        assertEquals(
            koRetimeKey(after, LyricsTranslationUiState.Idle),
            koRetimeKey(after, LyricsTranslationUiState.Loading),
        )
        assertEquals(
            koRetimeKey(after, LyricsTranslationUiState.Loading),
            koRetimeKey(after, LyricsTranslationUiState.SameLanguage),
        )
    }
}
