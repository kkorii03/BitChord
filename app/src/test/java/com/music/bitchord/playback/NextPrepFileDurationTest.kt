package com.music.bitchord.playback

import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next-row bundle used to compare the resolved song's catalogue length
 * against the pre-resolution queue row's duration. That row may omit its
 * duration or belong to the video, so a legacy music-video file was missed
 * and a valid song file could be refused. The file's own saved duration is the
 * only length that may be used for that comparison.
 */
class NextPrepFileDurationTest {

    private fun saved(
        videoId: String,
        durationText: String?,
        uri: String = "content://downloads/track",
        soundSourceVideoId: String? = null,
    ) = SavedSongMetadata(
        videoId = videoId,
        title = videoId,
        artist = "artist",
        durationText = durationText,
        uri = uri,
        soundSourceVideoId = soundSourceVideoId,
    )

    @Test
    fun `the recorded stream source supplies the file duration`() {
        val metadata = mapOf(
            "song" to saved("song", "3:00", soundSourceVideoId = "video"),
            "video" to saved("video", "5:00", soundSourceVideoId = "video"),
            "other" to saved("other", "4:00", uri = "content://downloads/other"),
        )

        assertEquals(
            300_000L,
            Downloads.savedFileDurationMs(metadata, "content://downloads/track", "song"),
        )
    }

    @Test
    fun `a legacy record keeps the duration filed under its own id`() {
        val metadata = mapOf("song" to saved("song", "5:20"))

        assertEquals(
            320_000L,
            Downloads.savedFileDurationMs(metadata, "content://downloads/track", "song"),
        )
    }

    @Test
    fun `another id naming the same file can supply the duration`() {
        val metadata = mapOf("video" to saved("video", "5:20"))

        assertEquals(
            320_000L,
            Downloads.savedFileDurationMs(metadata, "content://downloads/track", "song"),
        )
    }

    @Test
    fun `the saved file duration wins over the row duration`() {
        assertEquals(
            300_000L,
            nextPrepFileDurationMs(
                savedFileDurationMs = 300_000L,
                rowVideoId = "video",
                rowDurationMs = 310_000L,
                fileOwnerVideoId = "song",
            ),
        )
    }

    @Test
    fun `a row that does not own the file does not lend its duration`() {
        assertNull(
            nextPrepFileDurationMs(
                savedFileDurationMs = null,
                rowVideoId = "video",
                rowDurationMs = 300_000L,
                fileOwnerVideoId = "song",
            ),
        )
    }

    @Test
    fun `a row that owns the file may supply the duration when none was saved`() {
        assertEquals(
            300_000L,
            nextPrepFileDurationMs(
                savedFileDurationMs = null,
                rowVideoId = "song",
                rowDurationMs = 300_000L,
                fileOwnerVideoId = "song",
            ),
        )
    }

    @Test
    fun `a legacy video file with an unknown row duration is still refused`() {
        val metadata = mapOf("song" to saved("song", "5:20"))
        val fileDurationMs = nextPrepFileDurationMs(
            savedFileDurationMs = Downloads.savedFileDurationMs(
                metadata,
                "content://downloads/track",
                "song",
            ),
            rowVideoId = "video",
            rowDurationMs = null,
            fileOwnerVideoId = "song",
        )

        assertFalse(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = null,
                storedIsVideo = null,
                lengthSuspect = false,
                online = true,
                fileDurationMs = fileDurationMs,
                catalogDurationMs = 310_000L,
            ),
        )
    }

    @Test
    fun `a valid song file is not refused because the video row was longer`() {
        val metadata = mapOf("song" to saved("song", "5:00", soundSourceVideoId = "song"))
        val fileDurationMs = nextPrepFileDurationMs(
            savedFileDurationMs = Downloads.savedFileDurationMs(
                metadata,
                "content://downloads/track",
                "song",
            ),
            rowVideoId = "video",
            rowDurationMs = 310_000L,
            fileOwnerVideoId = "song",
        )

        assertTrue(
            nextPrepUseDownloadedFile(
                playId = "song",
                soundSourceId = "song",
                storedIsVideo = null,
                lengthSuspect = false,
                online = true,
                fileDurationMs = fileDurationMs,
                catalogDurationMs = 300_000L - SongVersionPolicy.MV_SOUND_SLACK_MS + 1L,
            ),
        )
    }
}
