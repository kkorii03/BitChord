package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRadioChannelParseTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `a watch queue row keeps the uploader channel id`() {
        val song = InnertubeParser.parseWatchQueue(
            json(
                """
                {
                  "contents": {
                    "playlistPanelVideoRenderer": {
                      "videoId": "abcdefghijk",
                      "title": { "runs": [ { "text": "A Cover" } ] },
                      "longBylineText": {
                        "runs": [
                          {
                            "text": "오화요",
                            "navigationEndpoint": {
                              "browseEndpoint": {
                                "browseId": "UCrealUploader",
                                "browseEndpointContextSupportedConfigs": {
                                  "browseEndpointContextMusicConfig": { "pageType": "ARTIST" }
                                }
                              }
                            }
                          }
                        ]
                      },
                      "lengthText": { "runs": [ { "text": "3:20" } ] },
                      "musicVideoType": "MUSIC_VIDEO_TYPE_OMV"
                    }
                  }
                }
                """.trimIndent(),
            ),
        ).single()

        assertEquals("UCrealUploader", song.channelId)
    }

    @Test
    fun `the player response names the video's own channel`() {
        val player = json(
            """
            {
              "videoDetails": {
                "videoId": "abcdefghijk",
                "channelId": "UCplaybackOwner"
              }
            }
            """.trimIndent(),
        )

        assertEquals("UCplaybackOwner", InnertubeParser.videoChannelId(player))
    }

    @Test
    fun `the player microformat is the fallback channel id`() {
        val player = json(
            """
            {
              "videoDetails": { "videoId": "abcdefghijk" },
              "microformat": {
                "playerMicroformatRenderer": { "externalChannelId": "UCmicroOwner" }
              }
            }
            """.trimIndent(),
        )

        assertEquals("UCmicroOwner", InnertubeParser.videoChannelId(player))
    }
}
