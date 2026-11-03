package com.music.bitchord.data.taste

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import org.junit.Assert.assertEquals
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
    fun `an old cover row with only the singer field stays an audio row`() {
        val song = LocalTrack(
            videoId = "cover-1",
            title = "Marigold",
            artist = "Ohwayo",
            coverSinger = "오화요",
        ).asSong()

        // QueueBuilder drops video rows from a mix with catalogue tracks, so
        // guessing a video here would hide the listener's cover favourites.
        assertFalse(song.isVideo)
        assertFalse(song.isVideoOrigin)
        assertEquals("오화요", song.coverSinger)
    }

    @Test
    fun `a local cover row survives a mix that has catalogue tracks`() {
        val cover = LocalTrack(
            videoId = "cover-1",
            title = "Marigold",
            artist = "Ohwayo",
            coverSinger = "오화요",
        ).asSong()
        val catalogue = Song(
            videoId = "song-1",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
        )

        val extended = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(catalogue, cover),
            limit = 5,
        )

        assertTrue(extended.any { it.videoId == "cover-1" })
    }
}
