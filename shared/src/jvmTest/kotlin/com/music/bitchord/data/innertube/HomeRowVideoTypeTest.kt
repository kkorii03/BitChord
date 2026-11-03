package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Home row's own `musicVideoType` decides whether it is a video, as it does
 * on every other row. Art is only the fallback: a video upload can carry square
 * art, and the catalogue track beside it can carry a widescreen still.
 */
class HomeRowVideoTypeTest {

    private fun homeRow(videoId: String, videoType: String, width: Int, height: Int) = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          {
                            "musicShelfRenderer": {
                              "title": { "runs": [ { "text": "Recents" } ] },
                              "contents": [
                                {
                                  "musicResponsiveListItemRenderer": {
                                    "playlistItemData": { "videoId": "$videoId" },
                                    "flexColumns": [
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [ { "text": "A Song" } ] }
                                        }
                                      },
                                      {
                                        "musicResponsiveListItemFlexColumnRenderer": {
                                          "text": { "runs": [ { "text": "An Artist" } ] }
                                        }
                                      }
                                    ],
                                    "thumbnail": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": {
                                          "thumbnails": [
                                            {
                                              "url": "https://i.ytimg.com/vi/$videoId/hq.jpg",
                                              "width": $width,
                                              "height": $height
                                            }
                                          ]
                                        }
                                      }
                                    },
                                    "navigationEndpoint": {
                                      "watchEndpoint": {
                                        "videoId": "$videoId",
                                        "watchEndpointMusicSupportedConfigs": {
                                          "watchEndpointMusicConfig": {
                                            "musicVideoType": "$videoType"
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              ]
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    private fun shelf(videoId: String, videoType: String, width: Int, height: Int) =
        InnertubeParser.parseHome(Json.parseToJsonElement(homeRow(videoId, videoType, width, height)).jsonObject)
            .single()

    @Test
    fun `an ATV row with a widescreen still is still the catalogue song`() {
        val item = shelf("atvWide1", "MUSIC_VIDEO_TYPE_ATV", width = 336, height = 188).items.single()

        assertEquals("A Song", item.title)
        assertFalse(item.isVideo)
    }

    @Test
    fun `an OMV row with square art is still a video`() {
        val item = shelf("omvSquare1", "MUSIC_VIDEO_TYPE_OMV", width = 544, height = 544).items.single()

        assertEquals("A Song", item.title)
        assertTrue(item.isVideo)
    }
}
