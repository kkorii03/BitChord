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

    // Sweep 11-18 (SW7-1): the sleeve's stats tap consumed every tap on the
    // clip's centre, so a hidden Spotify Canvas deck never came back from there.
    @Test
    fun `a canvas tap on the sleeve reaches the control deck`() {
        val source = source("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt")
        val slot = Regex("""if \(queueOpen \|\| lyricsOpen\) \{\s*Modifier\.clickable \{[\s\S]*?nerdStatsRevealTick\+\+""")
            .find(source)?.value
            ?: error("portrait sleeve tap not found")
        assertTrue(
            "the stats tap must step aside while the Canvas deck owns the tap",
            "spotifyCanvasPresentation && !mixing" in slot,
        )
    }

    // Sweep 11-18 (SW7-3): the lyrics retry hook ran only from openLyrics, so a
    // player restored onto lyrics never retried a transient miss.
    @Test
    fun `a player restored onto lyrics asks the lyrics retry once`() {
        val source = source("sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt")
        assertTrue(
            "the restored lyrics panel must run the open hook",
            Regex("""LaunchedEffect\(Unit\) \{\s*if \(lyricsOpen\) onLyricsOpen\?\.invoke\(\)""").containsMatchIn(source),
        )
    }

    // Sweep 11-18 (SW7-4): the downloaded-artwork key stat'ed the file on the
    // main thread for every downloaded row in a list.
    @Test
    fun `downloaded artwork keys are read off the main thread`() {
        val source = source("app/src/main/java/com/music/bitchord/ui/components/RemoteArtwork.kt")
        assertTrue(
            "the file stat must run on IO",
            "withContext(Dispatchers.IO) { versionedFileArtworkNow(value) }" in source,
        )
        assertTrue("the stat helper must be the IO wrapper", "private suspend fun versionedFileArtwork(" in source)
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
