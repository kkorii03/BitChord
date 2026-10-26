package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A downloaded catalogue release may only replace the upload when both are the
 * same song. A cover by another singer stays itself; an original MV still
 * becomes its downloaded song version.
 */
class DownloadedVersionReplacementTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun `a cover is not replaced by the downloaded original`() {
        val cover = song("cover", "Dance Hall", "Cover Singer", isVideo = true)
        val original = song("original", "Dance Hall", "Original Artist")

        assertFalse(SongVersionPolicy.sameSongVersion(cover, original))
    }

    @Test
    fun `a marked cover is not replaced even when the singer matches`() {
        val cover = song("cover", "Dance Hall (Cover)", "Original Artist", isVideo = true)
        val original = song("original", "Dance Hall", "Original Artist")

        assertFalse(SongVersionPolicy.sameSongVersion(cover, original))
    }

    @Test
    fun `an original mv is replaced by its downloaded song version`() {
        val mv = song("mv", "Dance Hall (Official Video)", "Original Artist", isVideo = true)
        val original = song("original", "Dance Hall", "Original Artist")

        assertTrue(SongVersionPolicy.sameSongVersion(mv, original))
    }
}
