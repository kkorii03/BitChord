package com.music.bitchord.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tapped video row must still be one when search history hands it back. */
class SearchHistoryVideoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a stored video track round-trips through search history`() {
        val entity = json.decodeFromString(
            SearchHistoryEntity.serializer(),
            """{"id":"v1","title":"Some MV","subtitle":"Channel","artworkUrl":null,"entityType":"TRACK","isVideo":true}""",
        )

        assertTrue(entity.toSong()!!.isVideo)
    }

    @Test
    fun `a legacy cover row is read as a video from its stored title`() {
        val entity = SearchHistoryEntity(
            id = "v2",
            title = "Marigold (cover)",
            subtitle = "오화요",
            artworkUrl = null,
            entityType = EntityType.TRACK,
        )

        assertTrue(entity.toSong()!!.isVideo)
    }

    @Test
    fun `an ordinary stored track stays a song`() {
        val entity = SearchHistoryEntity(
            id = "v3",
            title = "밤편지",
            subtitle = "IU",
            artworkUrl = null,
            entityType = EntityType.TRACK,
        )

        assertFalse(entity.toSong()!!.isVideo)
    }
}
