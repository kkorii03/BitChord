package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredVideoRowTest {

    private fun song(title: String, artist: String = "Channel", isVideo: Boolean = false) =
        Song(videoId = "v1", title = title, artist = artist, thumbnailUrl = null, isVideo = isVideo)

    @Test
    fun `the stored flag marks a video`() {
        assertTrue(CoverTitles.isVideoRow(song("Some MV", isVideo = true)))
    }

    @Test
    fun `a cover title marks a legacy row as a video`() {
        assertTrue(CoverTitles.isVideoRow(song("Marigold (cover)", artist = "오화요")))
    }

    @Test
    fun `an ordinary song is not a video`() {
        assertFalse(CoverTitles.isVideoRow(song("밤편지", artist = "IU")))
    }
}
