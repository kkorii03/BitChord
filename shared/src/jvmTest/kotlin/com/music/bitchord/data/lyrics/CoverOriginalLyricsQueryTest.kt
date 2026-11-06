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

    /**
     * 11-06 V6WWJNpIJN4: a lyrics channel's "가수(SINGER) - 곡 [가사/Lyrics]" was searched with the
     * singer as the song, and LRCLIB answered with another song of that name: the sheet shown
     * had 0 of 61 lines in common with what was sung.
     */
    @Test
    fun `a Hangul name with its Latin spelling before the dash is the singer`() {
        val query = CoverTitles.lyricsQuery(song("가수이름(SINGER) - 노래 제목 [가사/Lyrics]", channel = "가사채널"))
        assertEquals("노래 제목", query.title)
        assertEquals("가수이름(SINGER)", query.artist)
        assertEquals("노래 제목", LyricsQueryVariants.names(query.title, query.artist).first().title)
    }

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
