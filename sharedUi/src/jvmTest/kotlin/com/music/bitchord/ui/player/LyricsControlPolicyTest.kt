package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsControlPolicyTest {

    @Test
    fun aiTimingIsOfferedForAdjustableLyricsRegardlessOfGeminiSongMeaning() {
        // Regression: the sync drawer gated AI timing on the Gemini song-meaning
        // key even though AI timing runs on Deepgram. A listener with only a
        // Deepgram key lost the action.
        assertTrue(aiTimingActionEnabled(LyricsTimingUi(adjustable = true)))
        assertFalse(aiTimingActionEnabled(LyricsTimingUi(adjustable = false)))
    }

    @Test
    fun romanizationStaysAvailableWheneverThereAreLyrics() {
        // Regression: the romanization toggle was hidden whenever AI timing was
        // offered, which on Android is always, and the sync drawer never had
        // its own control — so it could not be reached at all.
        assertTrue(romanizationActionEnabled(hasLyrics = true))
        assertFalse(romanizationActionEnabled(hasLyrics = false))
    }
}
