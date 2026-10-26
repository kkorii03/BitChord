package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who a cover's play is credited to, from the row alone. The menu opens the
 * singer's channel and the scrobblers send the singer's name; both read this,
 * so the byline and the parsed cover fields have to answer the same way.
 */
class CoverCreditTest {

    @Test
    fun `a cover byline credits the singer and names the original`() {
        val song = Song(
            videoId = "cover1",
            title = "봄날",
            artist = "방탄소년단 · 아카네 리제 커버",
            thumbnailUrl = null,
            isVideo = true,
        )

        assertEquals("아카네 리제", CoverTitles.creditedArtist(song))
        assertEquals("방탄소년단", CoverTitles.creditedOriginalArtist(song))
    }

    @Test
    fun `parsed cover fields win over the displayed byline`() {
        val song = Song(
            videoId = "cover2",
            title = "봄날",
            artist = "아카네 리제",
            thumbnailUrl = null,
            isVideo = true,
            originalTitle = "봄날",
            originalArtist = "업로더",
            coverSinger = "아카네 리제",
            coverOriginalArtist = "방탄소년단",
        )

        assertEquals("아카네 리제", CoverTitles.creditedArtist(song))
        assertEquals("방탄소년단", CoverTitles.creditedOriginalArtist(song))
    }

    @Test
    fun `an ordinary track credits its artist and has no original`() {
        val song = Song(
            videoId = "track1",
            title = "Dynamite",
            artist = "BTS",
            thumbnailUrl = null,
        )

        assertEquals("BTS", CoverTitles.creditedArtist(song))
        assertNull(CoverTitles.creditedOriginalArtist(song))
    }
}
