package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sweep 11-18 (SW7ps-2): the nerd-stats change key left out the measured
 * stream bitrate, which lands off the main thread after the first build, so
 * the card never showed it (a fake FLAC kept its lossless badge).
 */
class KoNerdStatsKeyTest {

    @Test
    fun `the stats key changes when the measured bitrate lands`() {
        val key = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
            .substringAfter("internal fun publishNerdStats() {")
            .substringBefore("if (formatKey == lastNerdStatsKey) return")
        assertTrue("measured kbps must be part of the key", "measuredBitrateCache.get(" in key)
        assertTrue("a duration that becomes known must rebuild the card", "player.duration" in key)
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }
}
