package com.music.bitchord

import com.music.bitchord.ui.navigation.AppNavigator
import com.music.bitchord.ui.navigation.AppRoute
import com.music.bitchord.ui.screens.LyricsSettingsCatalog
import com.music.bitchord.ui.screens.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The lyrics page keeps every row the old "가사" group carried, in order. */
class LyricsSettingsCatalogTest {
    @Test
    fun everyLyricsSettingFromTheOldGroupIsOnTheLyricsCategoryPage() {
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
        assertEquals(SettingsCategory.LYRICS, LyricsSettingsCatalog.section)
        assertEquals(R.string.settings_lyrics, SettingsCategory.LYRICS.title)
    }

    @Test
    fun theLyricsRowsLiveOnTheirCategoryPageAndNotTheMainPage() {
        val sheet = generateSequence(File(".").canonicalFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt") }
            .first { it.exists() }
            .readText()
        assertTrue(
            "the lyrics rows must be gated by their category",
            sheet.contains("if (shows(SettingsCategory.LYRICS)) SearchableSettingsGroup("),
        )
        assertFalse(
            "the lyrics rows must not stay in an ungated group on the main page",
            sheet.contains("SearchableSettingsGroup(search, header = stringResource(R.string.settings_lyrics))"),
        )
    }

    @Test
    fun theLyricsCategoryOpensAPageOverSettingsAndBackReturnsToTheList() {
        val navigator = AppNavigator(
            detailDepth = { 0 },
            popDetail = { false },
            clearDetails = {},
        )
        navigator.push(AppRoute.Settings)
        navigator.push(AppRoute.SettingsSection(SettingsCategory.LYRICS))
        assertEquals(AppRoute.SettingsSection(SettingsCategory.LYRICS), navigator.visibleRoute())
        assertTrue(navigator.pop())
        assertEquals(AppRoute.Settings, navigator.visibleRoute())
    }
}
