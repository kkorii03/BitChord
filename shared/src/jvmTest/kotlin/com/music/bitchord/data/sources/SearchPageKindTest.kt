package com.music.bitchord.data.sources

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.SearchFilter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The version-switch search keeps its kind; dropping it logs every search as "typed". */
class SearchPageKindTest {

    @Test
    fun `the switch search keeps its kind through the music request`() = runBlocking {
        var seen: SearchKind? = null
        val page = YtMusicRepository.searchPage(
            query = "After LIKE",
            filter = SearchFilter.ALBUMS,
            anonymous = true,
            kind = SearchKind.VIDEO_SWITCH,
            music = { _, _, _, kind ->
                seen = kind
                Json.parseToJsonElement("{}").jsonObject
            },
        )

        assertTrue(page.isSuccess)
        assertEquals(SearchKind.VIDEO_SWITCH, seen)
    }
}
