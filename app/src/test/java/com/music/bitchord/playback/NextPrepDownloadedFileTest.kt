package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A next row resolved from its video to the catalogue song must not open a
 * file that was recorded as the video's sound, or one already marked suspect.
 * The playing-row path applies these rules; the bundle that replaces the next
 * row used to skip them and attach the old file regardless.
 */
class NextPrepDownloadedFileTest {

    @Test
    fun `a file from the same recording may be attached`() {
        assertTrue(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = "song",
                storedIsVideo = false,
                lengthSuspect = false,
                online = true,
            ),
        )
    }

    @Test
    fun `a file recorded from another upload is streamed online`() {
        assertFalse(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = "video",
                storedIsVideo = false,
                lengthSuspect = false,
                online = true,
            ),
        )
    }

    @Test
    fun `offline keeps a mismatched file because it is the only sound`() {
        assertTrue(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = "video",
                storedIsVideo = false,
                lengthSuspect = false,
                online = false,
            ),
        )
    }

    @Test
    fun `a file recorded as the video sound is not attached`() {
        assertFalse(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = "song",
                storedIsVideo = true,
                lengthSuspect = false,
                online = true,
            ),
        )
    }

    @Test
    fun `a length-suspect file is not attached even with matching provenance`() {
        assertFalse(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = "song",
                storedIsVideo = false,
                lengthSuspect = true,
                online = true,
            ),
        )
    }

    @Test
    fun `a legacy file with no provenance keeps the old play-file behaviour`() {
        assertTrue(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = null,
                storedIsVideo = null,
                lengthSuspect = false,
                online = true,
            ),
        )
    }
}
