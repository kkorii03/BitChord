package com.music.bitchord.playback.smart

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prior review: the process-wide timing store is a static, so a fallback
 * to `java.io.tmpdir` latched that directory for the whole process. Production
 * must keep the store in the app's files dir; tests inject their own directory.
 */
class LyricsTimingStorePathTest {

    private val source =
        File("src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt").readText()

    @Test
    fun `the timing store stays in the app's files dir`() {
        assertTrue(
            source.contains("File(context.applicationContext.filesDir, \"lyrics_timing_v1.json\")"),
        )
        assertFalse(source.contains("java.io.tmpdir"))
    }
}
