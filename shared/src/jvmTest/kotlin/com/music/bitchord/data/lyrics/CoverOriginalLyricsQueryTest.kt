package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A cover upload's title names the original in one of the shapes listeners report. */
class CoverOriginalLyricsQueryTest {

    private val originalSingers = CoverTitles.knownSingers

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = originalSingers
    }

    private fun song(title: String, channel: String = "오화요") = Song(
        videoId = "tomboy",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = true,
    )

    @Test
    fun `Title(Artist) searches the original title and artist`() {
        val query = CoverTitles.lyricsQuery(song("Tomboy((G)I-DLE)"))
        assertEquals("Tomboy", query.title)
        assertEquals("(G)I-DLE", query.artist)
        assertTrue(query.artistKnown)
        assertTrue(query.isVideo)

        // The name every provider is actually asked for.
        val names = LyricsQueryVariants.names("Tomboy((G)I-DLE)", "오화요")
        assertEquals("tomboy", names.first().title)
        assertEquals("(G)I-DLE", names.first().artist)
    }

    @Test
    fun `Artist - Title on a cover channel searches the song under the original artist`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val query = CoverTitles.lyricsQuery(song("(G)I-DLE - Tomboy"))
        assertEquals("Tomboy", query.title)
        assertEquals("(G)I-DLE", query.artist)
        assertTrue(query.artistKnown)

        val names = LyricsQueryVariants.names("(G)I-DLE - Tomboy", "오화요")
        assertEquals("tomboy", names.first().title)
        assertEquals("(G)I-DLE", names.first().artist)
    }

    @Test
    fun `Title slash cover by X searches the song under the singer`() {
        val query = CoverTitles.lyricsQuery(song("Tomboy / cover by 오화요"))
        assertEquals("Tomboy", query.title)
        assertEquals("오화요", query.artist)
        assertFalse(query.artistKnown)
    }

    @Test
    fun `Title (cover) searches the song under the uploader`() {
        val query = CoverTitles.lyricsQuery(song("Tomboy (cover)"))
        assertEquals("Tomboy", query.title)
        assertEquals("오화요", query.artist)
        assertFalse(query.artistKnown)
    }
}
