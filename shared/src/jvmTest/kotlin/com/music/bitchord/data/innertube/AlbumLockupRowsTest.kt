package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An album page can list its tracks in the newer `lockupViewModel` layout
 * instead of `musicResponsiveListItemRenderer`. The 1.8 fork read both; the
 * 1.9 merge only read lockups on a playlist shelf, so such an album came back
 * with no rows at all.
 */
class AlbumLockupRowsTest {

    @Test
    fun `album rows in the lockup layout are read`() {
        val songs = InnertubeParser.collectSongsDeep(
            Json.parseToJsonElement(
                """
                {"contents":[{"musicShelfRenderer":{"contents":[
                  {"lockupViewModel":{
                    "contentId":"lockupRow01",
                    "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                    "metadata":{"lockupMetadataViewModel":{
                      "title":{"content":"Lockup Row"}
                    }}
                  }}
                ]}}]}
                """,
            ),
        )

        assertEquals(listOf("lockupRow01"), songs.map { it.videoId })
        assertEquals("Lockup Row", songs.single().title)
    }
}
