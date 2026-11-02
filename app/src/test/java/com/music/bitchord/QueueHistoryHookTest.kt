package com.music.bitchord

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.9 merge kept 1.8's per-item history rotation in [PlaybackService] and
 * dropped v1.9's single-call [rotateExpiredHistory]. On repeat-all, jumping to a
 * late row then published the whole queue once per expired entry, which is what
 * took Bluetooth down on a 754-song queue. The helper and its test survived the
 * merge, but nothing called it, so the hook is checked in the source.
 */
class QueueHistoryHookTest {

    private val playbackService = File(repositoryRoot(), PLAYBACK_SERVICE).readText()

    @Test
    fun repeatAllRotatesExpiredHistoryInOneCall() {
        assertTrue(
            "PlaybackService lost the v1.9 rotateExpiredHistory call",
            "exoPlayer.rotateExpiredHistory(expiredHistory)" in playbackService,
        )
        assertFalse(
            "PlaybackService still moves expired history one item at a time",
            "repeat(expiredHistory)" in playbackService,
        )
    }

    private fun repositoryRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return directory
    }

    private companion object {
        const val PLAYBACK_SERVICE =
            "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
    }
}
