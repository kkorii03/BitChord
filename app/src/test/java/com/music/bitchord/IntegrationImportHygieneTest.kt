package com.music.bitchord

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge hygiene for the screen/settings lane.
 *
 * Independent branches added the same import to the same file, so the merged
 * tree carried duplicate import lines. They compile, but they make later
 * merge-conflict resolution and unused-import checks lie about the file.
 */
class IntegrationImportHygieneTest {

    private val files = listOf(
        "app/src/main/java/com/music/bitchord/MainActivity.kt",
        "app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt",
        "app/src/main/java/com/music/bitchord/ui/LyricsController.kt",
        "app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt",
        "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt",
    )

    @Test
    fun targetFilesDoNotImportTheSameSymbolTwice() {
        val root = repositoryRoot()
        val duplicates = files.flatMap { relative ->
            val imports = File(root, relative).readLines()
                .map(String::trim)
                .filter { it.startsWith("import ") }
            imports.groupingBy { it }.eachCount()
                .filterValues { it > 1 }
                .keys
                .map { "$relative: $it" }
        }

        assertTrue("repository root not found", root.isDirectory)
        assertEquals(emptyList<String>(), duplicates)
    }

    private fun repositoryRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return directory
    }
}
