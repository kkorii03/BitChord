package com.music.bitchord

import com.music.bitchord.ui.navigation.AppNavigator
import com.music.bitchord.ui.navigation.AppRoute
import com.music.bitchord.ui.screens.LyricsSettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyrics page keeps every row the old "가사" group carried, in order. */
class LyricsSettingsCatalogTest {
    @Test
    fun everyLyricsSettingFromTheOldGroupIsOnThePage() {
        assertEquals(
            listOf(
                "synced_lyrics",
                "lyrics_blur",
                "lyrics_sources",
                "translation_language",
                "gemini_translation",
                "auto_translate_lyrics",
                "auto_lyrics_timing",
                "ai_timing_ahead",
                "reset_download_sync",
                "deepgram_api_key",
                "ai_lyrics",
                "prefetch_translation",
                "keep_download_translation",
                "lock_screen_lyrics",
            ),
            LyricsSettingsCatalog.keys,
        )
        assertEquals(
            "the same setting must not be listed twice",
            LyricsSettingsCatalog.keys.size,
            LyricsSettingsCatalog.keys.toSet().size,
        )
    }

    @Test
    fun theLyricsRowOpensAPageOverSettingsAndBackReturnsToTheList() {
        val navigator = AppNavigator(
            detailDepth = { 0 },
            popDetail = { false },
            clearDetails = {},
        )
        navigator.push(AppRoute.Settings)
        navigator.push(AppRoute.Lyrics)
        assertEquals(AppRoute.Lyrics, navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertEquals(AppRoute.Settings, navigator.visibleRoute())
    }
}
