package com.music.bitchord.ui

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Test

/** A retry replaces a stale error with the skeleton, but never blanks a list. */
class LocalMusicLoadStateTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    @Test
    fun anErrorBecomesLoadingForTheRetry() {
        val failed: UiState<List<Song>> = UiState.Error("no local audio")
        assertEquals(UiState.Loading, localMusicLoadState(failed))
    }

    @Test
    fun aListOnScreenStaysUpDuringTheRefresh() {
        val rows: UiState<List<Song>> = UiState.Success(listOf(song("a")))
        assertEquals(rows, localMusicLoadState(rows))
    }
}
