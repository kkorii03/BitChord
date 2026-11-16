package com.music.bitchord.ko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** L19 / L20: a refused key entered again, and resting when no model could answer. */
class GeminiKeyRefusalTest {

    @Test
    fun aRefusedKeyEnteredAgainIsUsableAndTheWarningAgrees() {
        val old = KoLyricsTranslation.geminiApiKey
        try {
            KoLyricsTranslation.geminiApiKey = "AIzaTestKey"
            KoLyricsTranslation.rejectedGeminiKey = "AIzaTestKey"
            KoLyricsTranslation.geminiKeyRejected.value = true
            assertFalse(KoLyricsTranslation.geminiKeyUsable)

            KoLyricsTranslation.geminiApiKey = "AIzaTestKey"
            assertTrue(KoLyricsTranslation.geminiKeyUsable)
            assertEquals(!KoLyricsTranslation.geminiKeyUsable, KoLyricsTranslation.geminiKeyRejected.value)
        } finally {
            KoLyricsTranslation.geminiApiKey = old
        }
    }

    @Test
    fun quotaRefusalsWithAServerErrorStillRest() {
        assertTrue(KoLyricsTranslation.geminiShouldRest(quota = 3, unavailable = 1, tried = 4))
        assertTrue(KoLyricsTranslation.geminiShouldRest(quota = 4, unavailable = 0, tried = 4))
    }

    @Test
    fun noRestWithoutAQuotaRefusalOrWhenAModelAnsweredOtherwise() {
        assertFalse(KoLyricsTranslation.geminiShouldRest(quota = 0, unavailable = 4, tried = 4))
        assertFalse(KoLyricsTranslation.geminiShouldRest(quota = 2, unavailable = 1, tried = 4))
        assertFalse(KoLyricsTranslation.geminiShouldRest(quota = 0, unavailable = 0, tried = 0))
    }
}
