package com.music.bitchord.data.taste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video favourite keeps its flag when AutoPlay offers it back. */
class LocalTrackVideoFlagTest {

    @Test
    fun `a video row stays a video when rebuilt for autoplay`() {
        val song = LocalTrack(
            videoId = "video-1",
            title = "MV",
            artist = "Artist",
            isVideo = true,
        ).asSong()

        assertTrue(song.isVideo)
    }

    @Test
    fun `a plain track is not turned into a video`() {
        val song = LocalTrack(
            videoId = "song-1",
            title = "Song",
            artist = "Artist",
        ).asSong()

        assertFalse(song.isVideo)
    }
}
