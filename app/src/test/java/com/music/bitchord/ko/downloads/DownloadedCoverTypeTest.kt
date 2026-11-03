package com.music.bitchord.ko.downloads

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.SavedSongMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover's video identity has to be written with the download even when the
 * row it was tapped from lost the flag, and an older record's saved "(cover)"
 * title has to read it back.
 */
class DownloadedCoverTypeTest {

    private fun song(title: String, artist: String, isVideo: Boolean = false) = Song(
        videoId = "vRjfYdwM5L4",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    private fun record(title: String, artist: String) = SavedSongMetadata(
        videoId = "vRjfYdwM5L4",
        title = title,
        artist = artist,
        uri = "file:///downloads/cover.m4a",
        isVideo = false,
    )

    @Test
    fun `a cover downloaded from a row without the flag is recorded as a video`() {
        assertTrue(KoDownloadPipeline.recordedIsVideo(song("Marigold (cover)", "오화요")))
    }

    @Test
    fun `an ordinary song is still recorded as a song`() {
        assertFalse(KoDownloadPipeline.recordedIsVideo(song("밤편지", "IU")))
    }

    @Test
    fun `an older cover record reads as a video row`() {
        assertTrue(record("Marigold (cover)", "오화요").wasVideoRow())
        assertFalse(record("밤편지", "IU").wasVideoRow())
    }
}
