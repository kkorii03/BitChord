package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Album and playlist rows keep the upload type YouTube states for them.
 *
 * The fork fix on the 1.8 line (c078d94e) read `musicVideoType` before the
 * art-shape fallback: a Korean device's localised byline hid the type word,
 * and square-art OMV rows were played as songs while widescreen ATV rows were
 * played as videos. The 1.9 merge dropped that priority from
 * [InnertubeParser.parsePlaylistShelf]'s row reader.
 */
class PlaylistAlbumRowParseTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun playlistRow(
        videoId: String,
        musicVideoType: String,
        width: Int,
        height: Int,
    ): String = """
        {"musicResponsiveListItemRenderer":{
          "playlistItemData":{"videoId":"$videoId"},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{
              "text":{"runs":[{"text":"A Track"}]}
            }},
            {"musicResponsiveListItemFlexColumnRenderer":{
              "text":{"runs":[{"text":"An Artist"}]}
            }}
          ],
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
            {"url":"https://i.ytimg.com/vi/$videoId/hq.jpg","width":$width,"height":$height}
          ]}}},
          "navigationEndpoint":{"watchEndpoint":{
            "videoId":"$videoId",
            "watchEndpointMusicSupportedConfigs":{
              "watchEndpointMusicConfig":{"musicVideoType":"$musicVideoType"}
            }
          }}
        }}
    """.trimIndent()

    private fun playlist(vararg rows: String): String = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
          "musicPlaylistShelfRenderer":{"contents":[${rows.joinToString(",")}]}
        }}}}
    """.trimIndent()

    @Test
    fun `a square-art OMV row is still a video`() {
        val page = InnertubeParser.parsePlaylistShelf(
            json(playlist(playlistRow("omvSquare01", "MUSIC_VIDEO_TYPE_OMV", 480, 480))),
        )

        assertTrue(page?.songs?.single()?.isVideo == true)
    }

    @Test
    fun `a widescreen-art ATV row is still the catalogue song`() {
        val page = InnertubeParser.parsePlaylistShelf(
            json(playlist(playlistRow("atvWide0001", "MUSIC_VIDEO_TYPE_ATV", 480, 270))),
        )

        assertFalse(page?.songs?.single()?.isVideo == true)
    }

    @Test
    fun `a square-art private upload row stays a song`() {
        val page = InnertubeParser.parsePlaylistShelf(
            json(
                playlist(
                    playlistRow("privateUp01", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK", 480, 480),
                ),
            ),
        )

        assertFalse(page?.songs?.single()?.isVideo == true)
    }

    @Test
    fun `album pages read lockup video rows as songs`() {
        val songs = InnertubeParser.collectSongsDeep(
            json(
                """
                {"contents":{"sectionListRenderer":{"contents":[
                  {"musicShelfRenderer":{"contents":[
                    {"lockupViewModel":{
                      "contentId":"lockupTrack",
                      "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                      "metadata":{"lockupMetadataViewModel":{
                        "title":{"content":"Lockup Track"}
                      }}
                    }}
                  ]}}
                ]}}}
                """,
            ),
        )

        assertEquals(listOf("lockupTrack"), songs.map { it.videoId })
    }
}
