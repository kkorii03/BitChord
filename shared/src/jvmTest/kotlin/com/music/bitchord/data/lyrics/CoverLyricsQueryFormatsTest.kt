package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A cover upload that puts the original's artist in its title must be searched
 * under the original's title and artist, not the upload's whole title.
 */
class CoverLyricsQueryFormatsTest {

    private fun song(title: String, channel: String) = Song(
        videoId = "test",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = true,
    )

    private fun query(title: String, channel: String = "오화요") = CoverTitles.lyricsQuery(song(title, channel))

    @Test
    fun titleWithTheArtistInBracketsSearchesTheOriginal() {
        val read = assertNotNull(CoverTitles.read("Tomboy((G)I-DLE)", "오화요", null))
        assertEquals("Tomboy", read.title)
        assertEquals("(G)I-DLE", read.originalArtist)

        val q = query("Tomboy((G)I-DLE)")
        assertEquals("Tomboy", q.title)
        assertEquals("(G)I-DLE", q.artist)
        assertTrue(q.artistKnown)
        assertTrue(q.alternatives.contains("Tomboy" to "오화요"))

        val names = LyricsQueryVariants.names("Tomboy((G)I-DLE)", "오화요")
        assertEquals("tomboy", names.first().title)
        assertEquals("(G)I-DLE", names.first().artist)
    }

    @Test
    fun artistThenTitleSearchesTheOriginal() {
        val q = query("(G)I-DLE - Tomboy")
        assertEquals("Tomboy", q.title)
        assertEquals("(G)I-DLE", q.artist)

        val names = LyricsQueryVariants.names("(G)I-DLE - Tomboy", "오화요")
        assertEquals("tomboy", names.first().title)
        assertEquals("(G)I-DLE", names.first().artist)
    }

    @Test
    fun slashCoverBySearchesTheOriginal() {
        val q = query("Tomboy((G)I-DLE) / cover by 오화요")
        assertEquals("Tomboy", q.title)
        assertEquals("(G)I-DLE", q.artist)
        assertTrue(q.alternatives.contains("Tomboy" to "오화요"))
    }

    @Test
    fun titleThenCoverTagSearchesTheOriginal() {
        val q = query("Tomboy((G)I-DLE) (cover)")
        assertEquals("Tomboy", q.title)
        assertEquals("(G)I-DLE", q.artist)
    }

    @Test
    fun aPlainTitleCoverTagStillSearchesItsTitle() {
        val q = query("Tomboy (cover)")
        assertEquals("Tomboy", q.title)
        assertEquals("오화요", q.artist)
        assertFalse(q.artistKnown)
    }
}
