package com.music.bitchord.ui

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A search that finishes with no rows is a completed answer. Before this was
 * fixed `published()` turned that page into `UiState.Error(no_results)`, so the
 * screen offered a retry for a request that had already succeeded and its
 * "nothing matched" state could never be reached.
 */
class SearchEmptyPipelineTest {

    @Test
    fun aCompletedEmptyPageIsSuccessNotFailure() {
        val state = searchPageState(emptyList<SearchResult>())

        assertEquals(UiState.Success(emptyList<SearchResult>()), state)
        assertTrue((state as UiState.Success).data.isEmpty())
    }

    @Test
    fun rowsArePublishedAsSuccess() {
        val row = SearchResult.Browse(
            item = com.music.bitchord.data.model.BrowseItem(
                browseId = "album:1",
                title = "Album",
                subtitle = "Artist",
                thumbnailUrl = null,
                type = com.music.bitchord.data.model.BrowseType.ALBUM,
            ),
        )

        assertEquals(UiState.Success(listOf(row)), searchPageState(listOf(row)))
    }
}
