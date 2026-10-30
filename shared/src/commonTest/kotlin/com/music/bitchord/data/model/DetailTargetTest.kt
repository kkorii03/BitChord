package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decisions a detail screen makes about a card or a listing.
 *
 * Both used to be silent: an artist card with a non-`UC…` id opened as an
 * album, a track card did nothing, and a page whose only content was hidden
 * videos was replaced by "No tracks here".
 */
class DetailTargetTest {

    private fun card(
        browseId: String? = null,
        videoId: String? = null,
        isArtist: Boolean = false,
    ) = ShelfItem(
        title = "Card",
        subtitle = "",
        thumbnailUrl = null,
        videoId = videoId,
        browseId = browseId,
        isArtist = isArtist,
    )

    private fun song(id: String) = Song(
        videoId = id,
        title = "Song $id",
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun `an artist card stays an artist even without a UC id`() {
        assertEquals(BrowseType.ARTIST, card(browseId = "MPLAUC123", isArtist = true).detailBrowseType())
        assertEquals(BrowseType.ARTIST, card(browseId = "UC123", isArtist = false).detailBrowseType())
        assertEquals(BrowseType.ALBUM, card(browseId = "MPREb123", isArtist = false).detailBrowseType())
    }

    @Test
    fun `a card with only a video id is a track card`() {
        assertTrue(card(videoId = "vid").isTrackCard)
        assertFalse(card(browseId = "MPREb123", videoId = "vid").isTrackCard)
        assertFalse(card(browseId = "MPREb123").isTrackCard)
        assertFalse(card().isTrackCard)
    }

    @Test
    fun `hidden videos keep an otherwise empty page out of the error state`() {
        val hidden = listOf(song("video"))
        val state = detailListingState(emptyList(), hidden, "No tracks here")
        assertTrue(state is UiState.Success)
        assertEquals(emptyList(), (state as UiState.Success<List<Song>>).data)
    }

    @Test
    fun `a page with neither tracks nor hidden videos is the empty state`() {
        val state = detailListingState(emptyList(), emptyList(), "No tracks here")
        assertEquals(UiState.Error("No tracks here"), state)
    }

    @Test
    fun `an artist with only shelves is content, not the empty state`() {
        val state = detailListingState(emptyList(), emptyList(), "No tracks here", hasShelves = true)
        assertTrue(state is UiState.Success)
        assertEquals(emptyList(), (state as UiState.Success<List<Song>>).data)
    }

    @Test
    fun `tracks keep precedence over hidden videos`() {
        val songs = listOf(song("song"))
        val state = detailListingState(songs, listOf(song("video")), "No tracks here")
        assertEquals(UiState.Success(songs), state)
    }
}
