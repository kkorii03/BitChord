package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review V-4: the swap-version command carried no "swap from" id, so a skip or
 * a track end landing just before it made the service replace the next row with
 * the previous song's version. The command now names its row and the service
 * drops it when that row is no longer current.
 */
class KoSwapVersionCommandTest {

    @Test
    fun `a command for the row still playing applies`() {
        assertTrue(koSwapFromStillCurrent("V", "V"))
    }

    @Test
    fun `a command that arrives after the player moved on is dropped`() {
        assertFalse(koSwapFromStillCurrent("V", "W"))
        assertFalse(koSwapFromStillCurrent("V", null))
    }

    @Test
    fun `a sender that names no row keeps the old behaviour`() {
        assertTrue(koSwapFromStillCurrent(null, "W"))
    }

    @Test
    fun `the service checks the row before it touches anything`() {
        val block = source(SERVICE)
            .substringAfter("ACTION_SWAP_VERSION -> {")
            .substringBefore("ACTION_QUEUE_DRAG ->")
        val read = block.indexOf("getString(EXTRA_SWAP_FROM_ID)")
        val check = block.indexOf("koSwapFromStillCurrent(")
        assertTrue("the handler must read the from id", read >= 0)
        assertTrue("the handler must check it", check > read)
        assertTrue("checked before the music-only choice is recorded", check < block.indexOf("MusicOnlyPlayback.keep("))
        assertTrue("checked before the swap starts", check < block.indexOf("smoothSwapCurrentTrackVersion("))
    }

    @Test
    fun `every sender carries the row it swapped from`() {
        val send = source(CONNECTION)
            .substringAfter("fun MediaController.swapToVersion(")
            .substringBefore("\n}\n")
        assertTrue("the command must carry the from id", "EXTRA_SWAP_FROM_ID to fromMediaId" in send)
        assertTrue("the default is the row the controller shows", "fromMediaId: String? = currentMediaItem?.mediaId" in send)

        val activity = source(ACTIVITY)
        val calls = Regex("""\.swapToVersion\(([^\n]*)\)""").findAll(activity).map { it.groupValues[1] }.toList()
        assertEquals("five senders", 5, calls.size)
        val musicOnly = activity.substringAfter("suspend fun switchToMusicOnly(").substringBefore("suspend fun switchToVideo(")
        assertTrue("fromMediaId = song.videoId" in musicOnly)
        val toVideo = activity.substringAfter("suspend fun switchToVideo(").substringBefore("fun playFrom(")
        assertTrue("fromMediaId = song.videoId" in toVideo)
        val revert = activity.substringAfter("KoWrongAudioRevertEffect(").substringBefore("\n    }\n")
        assertTrue("fromMediaId = swappedAudioId" in revert)
    }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }

    private companion object {
        const val SERVICE = "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
        const val CONNECTION = "app/src/main/java/com/music/bitchord/playback/PlayerConnection.kt"
        const val ACTIVITY = "app/src/main/java/com/music/bitchord/MainActivity.kt"
    }
}
