package com.music.bitchord.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryActionTest {

    @Test
    fun `a saved track plays its own video`() {
        val entity = SearchHistoryEntity(
            id = "vid_123",
            title = "Test Song",
            subtitle = "Test Artist",
            artworkUrl = "https://example.test/art.jpg",
            entityType = EntityType.TRACK,
        )

        val action = entity.historyAction()

        assertTrue(action is SearchHistoryAction.Play)
        assertEquals("vid_123", (action as SearchHistoryAction.Play).song.videoId)
    }

    @Test
    fun `an old raw query searches again instead of playing a fake video id`() {
        val entity = SearchHistoryEntity(
            id = "q:some search",
            title = "some search",
            subtitle = "",
            artworkUrl = null,
            entityType = EntityType.TRACK,
        )

        assertEquals(SearchHistoryAction.Search("some search"), entity.historyAction())
    }

    @Test
    fun `albums artists and playlists open their page`() {
        val base = SearchHistoryEntity(
            id = "MPREb_album",
            title = "Album",
            subtitle = "Artist",
            artworkUrl = null,
            entityType = EntityType.ALBUM,
        )

        assertEquals(
            SearchHistoryAction.Open("MPREb_album", "Album", "Artist", null, EntityType.ALBUM),
            base.historyAction(),
        )
        assertTrue(base.copy(entityType = EntityType.ARTIST).historyAction() is SearchHistoryAction.Open)
        assertTrue(base.copy(entityType = EntityType.PLAYLIST).historyAction() is SearchHistoryAction.Open)
    }
}
