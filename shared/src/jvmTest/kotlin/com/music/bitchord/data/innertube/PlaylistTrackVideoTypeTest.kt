package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A playlist row's own `musicVideoType` says what it is; the thumbnail's shape
 * only stands in when the response omits it. 1.8 read the type, the 1.9 merge
 * read the art alone — so an official video with square art listed as a song,
 * and an ATV song with widescreen art listed as a video.
 */
class PlaylistTrackVideoTypeTest {

    private fun page(videoId: String, videoType: String, width: Int, height: Int) = InnertubeParser.parsePlaylistShelf(
        Json.parseToJsonElement(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
              "musicPlaylistShelfRenderer":{"contents":[
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
                  "thumbnail":{"musicThumbnailRenderer":{
                    "thumbnail":{"thumbnails":[
                      {"url":"https://i.ytimg.com/vi/$videoId/hq.jpg","width":$width,"height":$height}
                    ]}
                  }},
                  "navigationEndpoint":{"watchEndpoint":{
                    "videoId":"$videoId",
                    "watchEndpointMusicSupportedConfigs":{
                      "watchEndpointMusicConfig":{"musicVideoType":"$videoType"}
                    }
                  }}
                }}
              ]}
            }}}}
            """,
        ).jsonObject,
    )

    @Test
    fun `an official video stays a video even with square art`() {
        val song = page("omvSquare01", "MUSIC_VIDEO_TYPE_OMV", 100, 100)?.songs?.single()

        assertTrue(song?.isVideo == true)
    }

    @Test
    fun `an ATV song stays a song even with widescreen art`() {
        val song = page("atvWide0001", "MUSIC_VIDEO_TYPE_ATV", 480, 270)?.songs?.single()

        assertFalse(song?.isVideo == true)
    }
}
