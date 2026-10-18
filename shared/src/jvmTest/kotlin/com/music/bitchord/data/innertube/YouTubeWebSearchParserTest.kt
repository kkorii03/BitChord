package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The anonymous youtube.com search fallback must treat an unreadable body as
 * "no rows" so a failed fallback degrades into the empty Music result instead
 * of failing the whole search page.
 */
class YouTubeWebSearchParserTest {

    private val oneSong = """
        {"contents":{"videoRenderer":{
          "videoId":"dQw4w9WgXcQ",
          "title":{"runs":[{"text":"A Song"}]},
          "ownerText":{"runs":[{"text":"Artist - Topic"}]},
          "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg"}]}
        }}}
    """.trimIndent()

    @Test
    fun `a real search body still parses its music rows`() {
        val songs = YouTubeWebSearch.parseResponse(oneSong)

        assertEquals(listOf("dQw4w9WgXcQ"), songs.map { it.videoId })
        assertEquals("A Song", songs.single().title)
        assertEquals("Artist - Topic", songs.single().artist)
    }

    @Test
    fun `a body that is not a json object is no rows`() {
        assertTrue(YouTubeWebSearch.parseResponse("[]").isEmpty())
        assertTrue(YouTubeWebSearch.parseResponse("\"text\"").isEmpty())
        assertTrue(YouTubeWebSearch.parseResponse("null").isEmpty())
    }

    @Test
    fun `malformed json is no rows`() {
        assertTrue(YouTubeWebSearch.parseResponse("").isEmpty())
        assertTrue(YouTubeWebSearch.parseResponse("{not json").isEmpty())
    }

    @Test
    fun `a row with the wrong renderer shape is skipped`() {
        val songs = YouTubeWebSearch.parseResponse(
            """{"contents":{"videoRenderer":"unexpected"}}""",
        )

        assertTrue(songs.isEmpty())
    }
}
