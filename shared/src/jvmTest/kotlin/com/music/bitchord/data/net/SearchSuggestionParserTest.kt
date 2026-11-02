package com.music.bitchord.data.net

import com.music.bitchord.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The suggest answer carries both query text and music entity rows. Only the
 * entity rows may vouch for a completion, so podcast and video rows must not
 * let their words through.
 */
class SearchSuggestionParserTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun text(value: String) = """{ "text": { "runs": [ { "text": "$value" } ] } }"""

    private fun entity(
        title: String,
        subtitle: String,
        thumbnailWidth: Int,
        thumbnailHeight: Int,
        navigation: String,
        videoId: String? = null,
    ): String = """
        { "musicResponsiveListItemRenderer": {
          ${videoId?.let { "\"playlistItemData\": { \"videoId\": \"$it\" }," } ?: ""}
          "navigationEndpoint": $navigation,
          "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
            { "url": "https://example.invalid/$title", "width": $thumbnailWidth, "height": $thumbnailHeight }
          ] } } },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": ${text(title)} },
            { "musicResponsiveListItemFlexColumnRenderer": ${text(subtitle)} }
          ]
        } }
    """

    private val artistNavigation =
        """{ "browseEndpoint": { "browseId": "UCartist", "browseEndpointContextSupportedConfigs": { "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" } } } }"""

    private val albumNavigation =
        """{ "browseEndpoint": { "browseId": "MPREalbum", "browseEndpointContextSupportedConfigs": { "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" } } } }"""

    private fun watchNavigation(videoId: String) =
        """{ "watchEndpoint": { "videoId": "$videoId", "watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "MUSIC_VIDEO_TYPE_ATV" } } } }"""

    private val response = json(
        """
        { "contents": [
          { "searchSuggestionsSectionRenderer": { "contents": [
            { "searchSuggestionRenderer": {
              "suggestion": { "runs": [ { "text": "iu good day" } ] },
              "navigationEndpoint": { "searchEndpoint": { "query": "iu good day" } }
            } },
            { "searchSuggestionRenderer": {
              "suggestion": { "runs": [ { "text": "iu 뉴스" } ] },
              "navigationEndpoint": { "searchEndpoint": { "query": "iu 뉴스" } }
            } },
            ${entity("IU", "206M monthly audience", 60, 60, artistNavigation)},
            ${entity("Good Day", "Song • IU • 3:00", 60, 60, watchNavigation("SONG1"), "SONG1")},
            ${entity("뉴스", "Episode • Sep 1 • 코미디", 640, 360, watchNavigation("EP1"), "EP1")},
            ${entity("정치쇼", "Video • 토크쇼 • 48:00", 640, 360, watchNavigation("VID1"), "VID1")}
          ] } }
        ] }
        """,
    )

    @Test
    fun `music entity titles and credits are the only terms offered`() {
        val parsed = InnertubeParser.parseSearchSuggestions(response)

        assertEquals(listOf("iu good day", "iu 뉴스"), parsed.queries)
        assertEquals(listOf("IU", "206M monthly audience", "Good Day"), parsed.musicTerms)
        assertFalse(parsed.musicTerms.any { it.contains("뉴스") || it.contains("정치쇼") })
    }

    @Test
    fun `general completions have no music term and are dropped`() {
        val parsed = InnertubeParser.parseSearchSuggestions(response)

        assertEquals(
            listOf("iu", "iu good day"),
            suggestionRows("iu", parsed.queries, parsed.musicTerms),
        )
    }

    @Test
    fun `an album entity whose title says video still backs a completion`() {
        val videoTitle = json(
            """
            { "contents": [
              { "searchSuggestionsSectionRenderer": { "contents": [
                ${entity("Video Games", "Album • Lana Del Rey", 60, 60, albumNavigation)}
              ] } }
            ] }
            """,
        )

        val parsed = InnertubeParser.parseSearchSuggestions(videoTitle)

        assertEquals(listOf("Video Games", "Album • Lana Del Rey"), parsed.musicTerms)
    }
}