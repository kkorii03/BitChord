package com.music.bitchord.ui

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A continuation page must be able to rescue a first page that looked empty.
 *
 * Liked Music can answer its first page with no tracks and no hidden videos —
 * the page then sits in the error/empty state while its continuation carries
 * the only hidden videos. The merge used to require a Success page and threw
 * those rows away.
 */
class DetailContinuationMergeTest {

    private fun song(id: String, thumbnail: String? = null) = Song(
        videoId = id,
        title = "Song $id",
        artist = "Artist",
        thumbnailUrl = thumbnail,
    )

    private fun page(songs: UiState<List<Song>>) = DetailPage(
        browseId = "VLliked",
        title = "Liked Music",
        subtitle = "",
        thumbnailUrl = null,
        songs = songs,
        type = BrowseType.PLAYLIST,
    )

    @Test
    fun `an error page is promoted when a continuation brings hidden videos`() {
        val current = page(UiState.Error("No tracks here"))
        val merged = current.mergeDetailContinuation(
            newSongs = emptyList(),
            newSuggested = emptyList(),
            newHiddenSongs = listOf(song("video")),
            nextContinuation = null,
            artworkFallback = "cover",
        )

        assertTrue(merged != null)
        assertEquals(UiState.Success(emptyList<Song>()), merged!!.songs)
        assertEquals(listOf(song("video", "cover")), merged.hiddenSongs)
    }

    @Test
    fun `an error page is promoted when a continuation brings songs`() {
        val current = page(UiState.Error("No tracks here"))
        val merged = current.mergeDetailContinuation(
            newSongs = listOf(song("track")),
            newSuggested = emptyList(),
            newHiddenSongs = emptyList(),
            nextContinuation = "next",
            artworkFallback = null,
        )

        assertEquals(UiState.Success(listOf(song("track"))), merged!!.songs)
        assertEquals("next", merged.continuation)
    }

    @Test
    fun `an error page with nothing new stays untouched`() {
        val current = page(UiState.Error("No tracks here"))
        assertNull(
            current.mergeDetailContinuation(
                newSongs = emptyList(),
                newSuggested = emptyList(),
                newHiddenSongs = emptyList(),
                nextContinuation = "next",
                artworkFallback = null,
            ),
        )
    }

    @Test
    fun `a loading page is left alone`() {
        val current = page(UiState.Loading)
        assertNull(
            current.mergeDetailContinuation(
                newSongs = listOf(song("track")),
                newSuggested = emptyList(),
                newHiddenSongs = emptyList(),
                nextContinuation = null,
                artworkFallback = null,
            ),
        )
    }

    @Test
    fun `a page with no new rows reports no progress`() {
        val current = page(UiState.Success(listOf(song("track"))))
        assertNull(
            current.mergeDetailContinuation(
                newSongs = listOf(song("track")),
                newSuggested = emptyList(),
                newHiddenSongs = emptyList(),
                nextContinuation = "next",
                artworkFallback = null,
            ),
        )
    }
}
