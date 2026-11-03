package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two facts an old stored row can still be read from. */
class StoredVideoFlagTest {

    @Test
    fun `a downloaded video row is marked a video again`() {
        Downloads.setSavedMetadata(
            mapOf(
                "video-1" to SavedSongMetadata(
                    videoId = "video-1",
                    title = "MV",
                    artist = "Artist",
                    uri = "file:///downloads/video-1.webm",
                    isVideo = true,
                ),
            ),
        )
        try {
            val song = Song(videoId = "video-1", title = "MV", artist = "Artist", thumbnailUrl = null)
                .withStoredVideoFlag()

            assertTrue(song.isVideo)
        } finally {
            Downloads.setSavedMetadata(emptyMap())
        }
    }

    @Test
    fun `a cover title is marked a video again`() {
        val song = Song(
            videoId = "cover-1",
            title = "아이묭 - Marigold / cover by 오화요",
            artist = "Ohwayo",
            thumbnailUrl = null,
        ).withStoredVideoFlag()

        assertTrue(song.isVideo)
    }

    @Test
    fun `an ordinary stored track stays a track`() {
        val song = Song(videoId = "song-1", title = "Song", artist = "Artist", thumbnailUrl = null)
            .withStoredVideoFlag()

        assertFalse(song.isVideo)
    }
}
