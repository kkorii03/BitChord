package com.music.bitchord.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row stays a video through search history and its backup. */
class SearchHistoryVideoFlagTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a video row stays a video after the search history round trip`() {
        val entity = json.decodeFromString<SearchHistoryEntity>(
            """{"id":"video-1","title":"MV","subtitle":"Artist","artworkUrl":null,
               "entityType":"TRACK","isVideo":true}""",
        )

        assertTrue(entity.toSong()!!.isVideo)
        assertTrue(json.decodeFromString<SearchHistoryEntity>(json.encodeToString(entity)).toSong()!!.isVideo)
    }

    @Test
    fun `an old cover row without the flag is still a video`() {
        val entity = json.decodeFromString<SearchHistoryEntity>(
            """{"id":"cover-1","title":"아이묭 - Marigold / cover by 오화요","subtitle":"Ohwayo",
               "artworkUrl":null,"entityType":"TRACK"}""",
        )

        assertTrue(entity.toSong()!!.isVideo)
    }
}
