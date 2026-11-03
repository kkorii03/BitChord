package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared cover restore keeps a version the listener converted by hand. */
class CoverTitlesVideoFlagTest {

    private fun cover(isVideo: Boolean, isVideoOrigin: Boolean) = Song(
        videoId = "cover-1",
        title = "아이묭 - Marigold / cover by 오화요",
        artist = "Ohwayo",
        thumbnailUrl = null,
        isVideo = isVideo,
        isVideoOrigin = isVideoOrigin,
    )

    @Test
    fun `an old cover row without the flag is marked a video`() {
        assertTrue(CoverTitles.restoreVideoFlag(cover(isVideo = false, isVideoOrigin = false)).isVideo)
    }

    @Test
    fun `a converted cover version stays audio`() {
        val restored = CoverTitles.restoreVideoFlag(cover(isVideo = false, isVideoOrigin = true))

        assertFalse(restored.isVideo)
        assertTrue(restored.isVideoOrigin)
    }
}
