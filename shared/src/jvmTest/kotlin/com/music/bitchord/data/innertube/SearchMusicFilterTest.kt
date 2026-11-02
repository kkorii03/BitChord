package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The default search and its typeahead keep music rows only. A talk-show video
 * stays in the Videos tab, and a podcast episode waits for the Episodes tab.
 */
class SearchMusicFilterTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun row(
        videoId: String,
        title: String,
        subtitle: String,
        width: Int,
        height: Int,
        musicVideoType: String? = null,
    ): String {
        val type = musicVideoType?.let {
            """"watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "$it" } },"""
        } ?: ""
        return """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$videoId" },
          $type
          "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [
            { "url": "https://i.ytimg.com/vi/$videoId/hqdefault.jpg", "width": $width, "height": $height }
          ] } } },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "$title" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "$subtitle" } ] } } }
          ]
        } }
        """
    }

    private val response = json(
        """
        { "contents": [
          ${row("SONG0000001", "노래", "Song • 아티스트 • 3:00", 400, 400)},
          ${row("TALK0000001", "김태현의 정치쇼", "Video • 토크쇼 • 48:00", 640, 360)},
          ${row("EPIS0000001", "빵 터짐 주의", "Episode • Sep 1 • 코미디", 640, 360)}
        ] }
        """,
    )

    private fun ids(page: InnertubeParser.SearchPage) =
        page.rows.filterIsInstance<SearchResult.Track>().map { it.song.videoId }

    @Test
    fun defaultSearchKeepsSongsOnly() {
        assertEquals(listOf("SONG0000001"), ids(InnertubeParser.parseSearchPage(response)))
    }

    @Test
    fun episodesHaveTheirOwnTab() {
        assertEquals(
            listOf("EPIS0000001"),
            ids(InnertubeParser.parseSearchPage(response, includeEpisodes = true)),
        )
    }

    @Test
    fun videosStillBelongToTheVideosTab() {
        assertEquals(
            listOf("TALK0000001"),
            ids(InnertubeParser.parseSearchPage(response, includeVideos = true)),
        )
    }

    @Test
    fun anAtvSongWithWidescreenArtStaysOnTheAllTab() {
        val page = json(
            """
            { "contents": [
              ${row("ATV00000001", "넓은 커버", "Song • 아티스트 • 3:00", 640, 360, "MUSIC_VIDEO_TYPE_ATV")}
            ] }
            """,
        )

        assertEquals(listOf("ATV00000001"), ids(InnertubeParser.parseSearchPage(page)))
    }

    @Test
    fun anOfficialVideoWithSquareArtLeavesTheAllTab() {
        val page = json(
            """
            { "contents": [
              ${row("OMV00000001", "뮤직비디오", "Song • 아티스트 • 3:00", 400, 400, "MUSIC_VIDEO_TYPE_OMV")}
            ] }
            """,
        )

        assertEquals(emptyList(), ids(InnertubeParser.parseSearchPage(page)))
        assertEquals(
            listOf("OMV00000001"),
            ids(InnertubeParser.parseSearchPage(page, includeVideos = true)),
        )
    }
}
