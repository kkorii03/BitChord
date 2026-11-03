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

    @Test
    fun `an old cover row with only the singer field is marked a video again`() {
        val song = LocalTrack(
            videoId = "cover-1",
            title = "Marigold",
            artist = "Ohwayo",
            coverSinger = "오화요",
        ).asSong()

        assertTrue(song.isVideo)
        assertTrue(song.isVideoOrigin)
    }
}
