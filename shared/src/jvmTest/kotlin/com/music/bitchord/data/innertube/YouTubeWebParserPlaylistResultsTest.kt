package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Playlist cards from an anonymous playlist-filtered youtube.com search.
 * Fixtures are hand-made, one per shape the site currently mixes: a
 * `lockupViewModel` (content type PLAYLIST) and the legacy `playlistRenderer`.
 */
class YouTubeWebParserPlaylistResultsTest {

    @Test
    fun `lockup playlist cards become VL browse ids`() {
        val items = YouTubeWebParser.parsePlaylistResults(json(LOCKUP))

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("VLPLkpop123", item.browseId)
        assertEquals("K-pop Playlist", item.title)
        assertEquals("Moon Music", item.subtitle)
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", item.thumbnailUrl)
        assertNull(item.videoId)
    }

    @Test
    fun `legacy playlistRenderer cards become VL browse ids`() {
        val items = YouTubeWebParser.parsePlaylistResults(json(LEGACY))

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("VLPLballad99", item.browseId)
        assertEquals("Ballad Mix", item.title)
        assertEquals("Some Channel", item.subtitle)
        assertEquals("https://i.ytimg.com/vi/bbbbbbbbbbb/hqdefault.jpg", item.thumbnailUrl)
    }

    @Test
    fun `non playlist rows are ignored`() {
        val items = YouTubeWebParser.parsePlaylistResults(json(MIXED))

        assertEquals(listOf("VLPLkeep1", "VLPLkeep2"), items.map { it.browseId })
    }

    @Test
    fun `duplicate cards keep their first appearance`() {
        val items = YouTubeWebParser.parsePlaylistResults(json(MIXED))

        assertEquals(items.size, items.map { it.browseId }.toSet().size)
    }

    @Test
    fun `a search wrapper degrades gracefully`() {
        assertTrue(YouTubeWebSearch.parsePlaylistResponse("").isEmpty())
        assertTrue(YouTubeWebSearch.parsePlaylistResponse("{not json").isEmpty())
        assertEquals(
            listOf("VLPLkpop123"),
            YouTubeWebSearch.parsePlaylistResponse(LOCKUP).map { it.browseId },
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val LOCKUP = """
            {"contents":{"itemSectionRenderer":{"contents":[
              {"lockupViewModel":{
                "contentId":"PLkpop123",
                "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                "contentImage":{"thumbnailViewModel":{"image":{"sources":[
                  {"url":"https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg"}
                ]}}},
                "metadata":{"lockupMetadataViewModel":{
                  "title":{"content":"K-pop Playlist"},
                  "metadata":{"contentMetadataViewModel":{"metadataRows":[
                    {"metadataParts":[{"text":{"content":"Moon Music"}}]}
                  ]}}
                }}
              }}
            ]}}}
        """

        const val LEGACY = """
            {"contents":{"sectionListRenderer":{"contents":[
              {"playlistRenderer":{
                "playlistId":"PLballad99",
                "title":{"simpleText":"Ballad Mix"},
                "shortBylineText":{"runs":[{"text":"Some Channel"}]},
                "videoCountText":{"runs":[{"text":"25 videos"}]},
                "thumbnail":{"thumbnails":[
                  {"url":"https://i.ytimg.com/vi/bbbbbbbbbbb/hqdefault.jpg"}
                ]}
              }}
            ]}}}
        """

        const val MIXED = """
            {"contents":{"itemSectionRenderer":{"contents":[
              {"lockupViewModel":{
                "contentId":"PLkeep1",
                "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Keep One"}}}
              }},
              {"lockupViewModel":{
                "contentId":"vidVideo001",
                "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Not A Playlist"}}}
              }},
              {"lockupViewModel":{
                "contentId":"OLAK5uy_album",
                "contentType":"LOCKUP_CONTENT_TYPE_ALBUM",
                "metadata":{"lockupMetadataViewModel":{"title":{"content":"Not A Playlist"}}}
              }},
              {"playlistRenderer":{
                "playlistId":"PLkeep2",
                "title":{"simpleText":"Keep Two"}
              }},
              {"playlistRenderer":{
                "playlistId":"PLkeep1",
                "title":{"simpleText":"Keep One Again"}
              }}
            ]}}}
        """
    }
}
