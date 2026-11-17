package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review X8: every finished download started a translation (Gemini, up to every
 * model, else Google) with no power-saving check, unlike the rest of the ahead
 * pass, so a playlist download in power saving sent one request per song.
 */
class TranslationDownloadGateTest {

    @Test
    fun `power saving defers the download translation`() {
        assertFalse(TranslationAhead.shouldTranslateDownload(keepSetting = true, heavyWorkAllowed = false))
    }

    @Test
    fun `the setting off never translates`() {
        assertFalse(TranslationAhead.shouldTranslateDownload(keepSetting = false, heavyWorkAllowed = true))
    }

    @Test
    fun `setting on and heavy work allowed translates as before`() {
        assertTrue(TranslationAhead.shouldTranslateDownload(keepSetting = true, heavyWorkAllowed = true))
    }
}
