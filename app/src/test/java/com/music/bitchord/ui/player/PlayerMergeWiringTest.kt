package com.music.bitchord.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Player behaviours the 1.9 merge dropped from the Korean fork. */
class PlayerMergeWiringTest {

    @Test
    fun `portrait title stops crawling while paused`() {
        val source = source("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt")
        val title = Regex("""text = song\.title,[\s\S]*?onOverflowChange[\s\S]*?opensPage\(song\.albumId""")
            .find(source)?.value
            ?: error("portrait title marquee not found")
        assertTrue("title marquee needs the playing gate", "running = isPlaying" in title)
    }

    @Test
    fun `low power never prepares the full-screen blur`() {
        val source = source("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt")
        val prepare = Regex("""prepare = [\s\S]*?playerPrewarmStage >= 1""").find(source)?.value
            ?: error("full artwork blur prepare rule not found")
        assertTrue("low power must keep the cheap mesh path", "!lowPower" in prepare)
    }

    @Test
    fun `canvas clips keep their loop window`() {
        val source = source("app/src/main/java/com/music/bitchord/ui/player/CanvasArtworkPlayer.kt")
        assertTrue("a video canvas must be clipped to its loop", "clipStartMs" in source)
        assertTrue("the clipping must reach the player's media item", "setClippingConfiguration(" in source)
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText()
    }
}
