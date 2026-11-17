package com.music.bitchord

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review S2-2 / S2-2b: a failed lookup (offline, timeout) was cached as an
 * answer, so "go to album / artist" and the video/audio switch row stayed
 * hidden for that song until the screen was recreated.
 */
class FailedLookupNotCachedTest {

    @Test
    fun `track links are cached only when the lookup answered`() {
        val main = File("src/main/java/com/music/bitchord/MainActivity.kt").readText()
        assertTrue(
            "trackLinksCache must be written only on success",
            "if (answer.isSuccess) trackLinksCache[current.videoId] = found" in main,
        )
        assertFalse(
            "the unconditional cache write is back",
            Regex("""\n\s*trackLinksCache\[current\.videoId] = found""").containsMatchIn(main),
        )
    }

    @Test
    fun `alternate version is cached only when the lookup answered`() {
        val effects = File("src/main/java/com/music/bitchord/ko/KoVersionEffects.kt").readText()
        assertTrue(
            "alternateVersionCache must skip a failed lookup",
            "if (exists != null) alternateVersionCache[song.videoId] = exists" in effects,
        )
        assertFalse(
            "a failure must not read as \"no other version\"",
            ".getOrDefault(false)" in effects.substringAfter("alternateVersionCache[song.videoId]?.let"),
        )
    }
}
