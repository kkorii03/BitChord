package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/** Old rows on disk read the way a fresh upload row now does. */
class StoredSongDisplayTest {

    @Test
    fun `a stored soundtrack upload reads as its song and performer`() {
        val stored = Song(
            videoId = "joker-1",
            title = "Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack",
            artist = "Original Soundtrack",
            thumbnailUrl = null,
        )

        val shown = StoredSongDisplay.clean(stored)

        assertEquals("Rock & Roll Part II", shown.title)
        assertEquals("Gary Glitter", shown.artist)
        assertEquals("Joker OST", shown.albumName)
    }

    @Test
    fun `a stored row with no performer never shows a soundtrack label`() {
        val stored = Song(
            videoId = "joker-2",
            title = "Following Sophie | Joker OST - Original Soundtrack",
            artist = "Original Soundtrack",
            thumbnailUrl = null,
        )

        val shown = StoredSongDisplay.clean(stored)

        assertEquals("Following Sophie", shown.title)
        assertEquals("", shown.artist)
        assertEquals("Joker OST", shown.albumName)
    }

    @Test
    fun `a stored cover keeps its singer instead of its uploader`() {
        val stored = Song(
            videoId = "cover-1",
            title = "아이묭 - Marigold / cover by 오화요",
            artist = "Ohwayo",
            thumbnailUrl = null,
        )

        val shown = StoredSongDisplay.clean(stored)

        assertEquals("Marigold (cover)", shown.title)
        assertEquals("오화요", shown.artist)
    }

    @Test
    fun `a catalogue row with release metadata is left alone`() {
        val stored = Song(
            videoId = "catalogue-1",
            title = "Dynamite (Official Video)",
            artist = "BTS",
            thumbnailUrl = null,
            albumId = "MPREb_catalogue",
        )

        assertEquals(stored, StoredSongDisplay.clean(stored))
    }
}
