package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The MediaStore row reader's fallbacks are user-visible text. The v1.9 merge
 * replaced the Korean resources 1.8 used with English literals, so an unknown
 * tag showed "Unknown Artist" / "Track 12" in the Korean build.
 */
class LocalMediaFallbackTextTest {

    @Test
    fun `media store fallbacks come from the app's resources`() {
        val source = sourceFile.readText()
        val reader = source.substringAfter("private class AudioColumns")
            .substringBefore("private val ALBUM_ART_URI")
        assertTrue(
            "the title fallback must use R.string.local_track_fallback",
            reader.contains("R.string.local_track_fallback"),
        )
        assertTrue(
            "the artist fallback must use R.string.unknown_artist",
            reader.contains("R.string.unknown_artist"),
        )
    }

    private val sourceFile: File by lazy {
        var directory = File(".").canonicalFile
        while (!File(directory, "settings.gradle.kts").exists()) {
            directory = directory.parentFile ?: error("repository root not found")
        }
        File(directory, "app/src/main/java/com/music/bitchord/data/LocalMediaRepository.kt")
    }
}
