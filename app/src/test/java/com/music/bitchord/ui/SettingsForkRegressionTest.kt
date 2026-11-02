package com.music.bitchord.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings rows the v1.9 merge changed away from the Korean fork.
 *
 * Source-level checks, like [IntegrationImportHygieneTest]: the rows are
 * Compose and cannot be composed in a JVM test, but each lost piece is a
 * literal here that a reader can find and the merge can be told apart from.
 */
class SettingsForkRegressionTest {

    @Test
    fun genreRowStaysOffAndClosedWithoutLastfmKey() {
        val block = source("app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt")
            .substringAfter("val workOutGenresTitle")
            .substringBefore("val exportDataTitle")

        assertTrue(
            "without a Last.fm key the genre switch must read off, as it did on 1.8",
            block.contains("checked = replayGenres && genresSupported"),
        )
        assertEquals(
            "the row and its switch must both be disabled without a key",
            2,
            Regex("enabled = genresSupported").findAll(block).count(),
        )
    }

    @Test
    fun lyricsEntryKeepsTheOldSearchTerm() {
        val src = source("app/src/main/java/com/music/bitchord/ko/KoLyricsEntry.kt")

        assertTrue("the 1.8 search term must still find the lyrics page", src.contains("\"가사 소스\""))
        assertTrue("the current label must still find it", src.contains("\"가사 제공처\""))
    }

    @Test
    fun settingsGroupsKeepTheirFooters() {
        val src = source("app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt")

        for (key in listOf(
            "settings_footer_downloads",
            "settings_footer_playback",
            "show_cache_folder_subtitle",
            "settings_footer_advanced",
        )) {
            assertTrue("$key was shown on the 1.8 settings page", src.contains("R.string.$key"))
        }
    }

    private fun source(relative: String): String =
        File(repositoryRoot(), relative).readText()

    private fun repositoryRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return directory
    }
}