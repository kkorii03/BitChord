package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Home rows state what they are in the row's own `musicVideoType`. The 1.9
 * merge replaced that with art shape alone, so an official video with square
 * art played as a song and the catalogue song beside it played as a video.
 */
class HomeRowVideoTypeTest {

    private fun homeRow(videoType: String, width: Int, height: Int): String = """
        {
          "contents": {
            "musicShelfRenderer": {
              "contents": [
                {
                  "musicResponsiveListItemRenderer": {
                    "playlistItemData": { "videoId": "row1" },
                    "musicVideoType": "$videoType",
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
                            { "url": "https://example.test/art.jpg", "width": $width, "height": $height }
                          ]
                        }
                      }
                    }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    private fun row(videoType: String, width: Int, height: Int) =
        InnertubeParser.collectSongsDeep(Json.parseToJsonElement(homeRow(videoType, width, height))).single()

    @Test
    fun `an OMV home row stays a video even with square art`() {
        assertTrue(row("MUSIC_VIDEO_TYPE_OMV", 60, 60).isVideo)
    }

    @Test
    fun `an ATV home row is the catalogue song even with wide art`() {
        assertFalse(row("MUSIC_VIDEO_TYPE_ATV", 1280, 720).isVideo)
    }
}
