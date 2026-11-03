package com.music.bitchord.ko.downloads

import com.music.bitchord.download.SavedSongMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Old download records without an upload type still read a cover as a video. */
class SavedSongMetadataVideoFlagTest {

    @Test
    fun `a legacy cover record is a video row from its byline`() {
        val meta = SavedSongMetadata(
            videoId = "vid_cover",
            title = "Song",
            artist = "Original Artist · Singer 커버",
            uri = "file:///downloads/song.m4a",
        )

        assertTrue(meta.wasVideoRow())
    }

    @Test
    fun `an explicit audio record stays audio`() {
        val meta = SavedSongMetadata(
            videoId = "vid_song",
            title = "Song",
            artist = "Artist",
            uri = "file:///downloads/song.m4a",
            isVideo = false,
        )

        assertFalse(meta.wasVideoRow())
    }
}
