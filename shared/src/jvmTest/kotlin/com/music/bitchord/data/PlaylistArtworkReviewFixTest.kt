package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Review follow-ups for the playlist artwork fallback.
 *
 * A catalogue-linked row used to leave the cleaner before the video still was
 * filled, and a header used to take the first row's still before looking at a
 * later row's real cover. Both left the playlist page with a blank or
 * first-row-only picture even though the data carried a better one.
 */
class PlaylistArtworkReviewFixTest {

    @Test
    fun `an album-linked stored row without a thumbnail gets the video still`() {
        val stored = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Night Flower (야화) (cover)",
            artist = "오화요",
            albumId = "MPREb_album",
            thumbnailUrl = null,
        )

        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            StoredSongDisplay.clean(stored).thumbnailUrl,
        )
    }

    @Test
    fun `a blank album-linked thumbnail is replaced by the video still`() {
        val stored = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Night Flower (야화) (cover)",
            artist = "오화요",
            albumId = "MPREb_album",
            thumbnailUrl = "   ",
        )

        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            StoredSongDisplay.clean(stored).thumbnailUrl,
        )
    }

    @Test
    fun `a local album-less row keeps no video fallback`() {
        val local = Song(
            videoId = "content://media/1",
            title = "Local",
            artist = "오화요",
            albumId = "MPREb_album",
            thumbnailUrl = null,
            localUri = "content://media/1",
        )

        assertNull(StoredSongDisplay.clean(local).thumbnailUrl)
    }
}
