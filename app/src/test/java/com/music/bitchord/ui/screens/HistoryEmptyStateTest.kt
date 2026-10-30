package com.music.bitchord.ui.screens

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.historyPageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An empty account history must say so instead of drawing a blank page. */
class HistoryEmptyStateTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    @Test
    fun aSignedInAccountWithNothingPlayedIsEmpty() {
        val state: UiState<List<Song>> = UiState.Success(emptyList())
        assertTrue(historyHasNoSongs(state, signedIn = true))
    }

    @Test
    fun aCompletedEmptyHistoryPageReachesTheEmptyState() {
        val state = historyPageState(emptyList())

        assertTrue(state is UiState.Success)
        assertTrue(historyHasNoSongs(state, signedIn = true))
    }

    @Test
    fun songsOrAnUnfinishedRequestAreNotAnEmptyHistory() {
        val rows: UiState<List<Song>> = UiState.Success(listOf(song("a")))
        val loading: UiState<List<Song>> = UiState.Loading
        val failed: UiState<List<Song>> = UiState.Error("연결 실패")

        assertFalse(historyHasNoSongs(rows, signedIn = true))
        assertFalse(historyHasNoSongs(loading, signedIn = true))
        assertFalse(historyHasNoSongs(failed, signedIn = true))
    }

    @Test
    fun aGuestHasItsOwnSignInStateRatherThanAnEmptyHistory() {
        val state: UiState<List<Song>> = UiState.Success(emptyList())
        assertFalse(historyHasNoSongs(state, signedIn = false))
    }
}
