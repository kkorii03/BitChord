package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The URL rewrite behind list rows: every legacy 4:3 still becomes the same
 * video's 16:9 `hq720` frame, and nothing else is touched.
 */
class ArtworkThumbnailTest {

    private val square = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg"

    @Test
    fun `legacy jpg sizes become hq720`() {
        val sizes = listOf(
            "hqdefault", "sddefault", "default", "0", "1", "2", "3", "hq1", "hq2", "hq3",
        )
        sizes.forEach { size ->
            assertEquals(
                square,
                squareFriendlyThumbnail("https://i.ytimg.com/vi/dQw4w9WgXcQ/$size.jpg"),
                size,
            )
        }
    }

    @Test
    fun `webp and query strings are rewritten too`() {
        assertEquals(
            square,
            squareFriendlyThumbnail("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/hqdefault.webp"),
        )
        assertEquals(
            square,
            squareFriendlyThumbnail("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc&rs=xyz"),
        )
        assertEquals(
            square,
            squareFriendlyThumbnail("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/sddefault.webp?foo=1"),
        )
        assertEquals(
            square,
            squareFriendlyThumbnail("http://i.ytimg.com/vi/dQw4w9WgXcQ/0.jpg"),
        )
    }

    @Test
    fun `true 16 by 9 thumbnails are left alone`() {
        listOf("mqdefault.jpg", "hq720.jpg", "maxresdefault.jpg").forEach { name ->
            val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/$name"
            assertEquals(url, squareFriendlyThumbnail(url))
        }
    }

    @Test
    fun `other hosts and square artwork are untouched`() {
        val others = listOf(
            "https://lh3.googleusercontent.com/cover=w1200-h1200",
            "https://yt3.ggpht.com/avatar=s88",
            "https://is1-ssl.mzstatic.com/image/thumb/600x600bb.jpg",
            "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://i.ytimg.com/vi/too-short/hqdefault.jpg",
            "not a url",
        )
        others.forEach { assertEquals(it, squareFriendlyThumbnail(it)) }
    }

    @Test
    fun `null stays null`() {
        assertNull(squareFriendlyThumbnail(null))
    }

    @Test
    fun `a row without artwork falls back to the video still`() {
        val song = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Song",
            artist = "Artist",
            thumbnailUrl = null,
        )

        assertEquals(square, song.artworkAt(160))
    }

    @Test
    fun `a local row gets no YouTube fallback`() {
        val song = Song(
            videoId = "content://media/1",
            title = "Local",
            artist = "Artist",
            thumbnailUrl = null,
            localUri = "content://media/1",
        )

        assertNull(song.artworkAt(160))
    }

    @Test
    fun `videoThumbnailUrl only accepts a video id`() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            videoThumbnailUrl("dQw4w9WgXcQ"),
        )
        assertNull(videoThumbnailUrl("too-short"))
        assertNull(videoThumbnailUrl(null))
    }
}
