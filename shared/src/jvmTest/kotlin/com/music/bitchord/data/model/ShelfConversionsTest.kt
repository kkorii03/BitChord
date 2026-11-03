package com.music.bitchord.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cards and playable rows must keep the upload type they carry. */
class ShelfConversionsTest {

    @Test
    fun `a video card stays a video as its song`() {
        val item = ShelfItem(
            title = "Video Song",
            subtitle = "Song • Video Artist",
            thumbnailUrl = null,
            videoId = "vid_video",
            browseId = null,
            isVideo = true,
        )

        val song = item.toSong()

        assertNotNull(song)
        assertTrue(song!!.isVideo)
        assertEquals("vid_video", song.videoId)
        assertEquals("Video Artist", song.artist)
    }

    @Test
    fun `a video row stays a video as its card`() {
        val song = Song(
            videoId = "vid_video",
            title = "Video Song",
            artist = "Video Artist",
            thumbnailUrl = null,
            isVideo = true,
        )

        val item = song.toShelfItem()

        assertTrue(item.isVideo)
        assertEquals("vid_video", item.videoId)
    }
}
