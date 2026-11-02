package com.music.bitchord.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 1.9 fork split moved the settings rows into ko/ files and, on the way,
 * dropped copy that both 1.8 and v1.9 showed: the playback row explanations,
 * the Automix state line, the settings footers and one Korean search alias.
 */
class KoSettingsCopyRegressionTest {
    @Test
    fun playbackRowsKeepTheirExplanations() {
        val rows = source("app/src/main/java/com/music/bitchord/ko/KoPlaybackSettingsRows.kt")
        assertTrue(
            "the headphone row must explain what it resumes",
            rows.contains("R.string.resume_on_headphones_subtitle"),
        )
        assertTrue(
            "the personalised autoplay row must explain its ordering",
            rows.contains("R.string.personalised_autoplay_subtitle"),
        )
        assertTrue(
            "the video-radio row must say it keeps the video world",
            rows.contains("R.string.video_radio_continue_subtitle"),
        )
    }

    @Test
    fun automixRowSaysWhetherItIsOn() {
        val rows = source("app/src/main/java/com/music/bitchord/ko/KoSettingsRowsFork.kt")
        assertTrue("enabled Automix subtitle", rows.contains("R.string.automix_enabled_subtitle"))
        assertTrue("disabled Automix subtitle", rows.contains("R.string.automix_disabled_subtitle"))
    }

    @Test
    fun settingsFootersAreStillShown() {
        val sheet = source("app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt")
        assertTrue("downloads footer", sheet.contains("R.string.settings_footer_downloads"))
        assertTrue("playback footer", sheet.contains("R.string.settings_footer_playback"))
        assertTrue("storage footer", sheet.contains("R.string.show_cache_folder_subtitle"))
        assertTrue("advanced footer", sheet.contains("R.string.settings_footer_advanced"))
    }

    @Test
    fun theOldKoreanLyricsSourceSearchStillFindsThePage() {
        val entry = source("app/src/main/java/com/music/bitchord/ko/KoLyricsEntry.kt")
        assertTrue("1.8 searched the lyrics rows with 가사 소스", entry.contains("가사 소스"))
    }

    @Test
    fun replayGenresSwitchStaysClosedWithoutLastfmKey() {
        val sheet = source("app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt")
        val row = sheet
            .substringAfter("val workOutGenresTitle = stringResource(R.string.work_out_genres)")
            .substringBefore("val exportDataTitle")
        val toggle = row.substringAfter("IosSwitch(").substringBefore(")")
        assertTrue(
            "1.8 showed the switch off when Last.fm has no key",
            toggle.contains("checked = replayGenres && genresSupported"),
        )
        assertTrue(
            "1.8 closed the switch itself, not only the row around it",
            toggle.contains("enabled = genresSupported"),
        )
    }

    private fun source(path: String): String {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").exists() }
        return File(root, path).readText()
    }
}
