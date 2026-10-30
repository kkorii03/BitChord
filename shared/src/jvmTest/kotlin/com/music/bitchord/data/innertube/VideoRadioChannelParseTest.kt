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

    @Test
    fun `a lockup row reads the uploader channel id from metadata command runs`() {
        val song = YouTubeWebParser.parsePlaylist(
            json(
                """
                {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
                  {"itemSectionRenderer":{"contents":[
                    {"lockupViewModel":{
                      "contentId":"lockupChan1",
                      "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                      "metadata":{"lockupMetadataViewModel":{
                        "title":{"content":"A Cover"},
                        "metadata":{"contentMetadataViewModel":{"metadataRows":[
                          {"metadataParts":[{"text":{
                            "content":"Real Uploader",
                            "commandRuns":[{"startIndex":0,"length":13,"onTap":{"innertubeCommand":{
                              "browseEndpoint":{"browseId":"UClockupOwner","canonicalBaseUrl":"/@real-uploader"}
                            }}}]
                          }}]},
                          {"metadataParts":[{"text":{"content":"1M views"}}]}
                        ]}}
                      }}
                    }}
                  ]}}
                ]}}}}]}}}
                """.trimIndent(),
            ),
        ).songs.single()

        assertEquals("UClockupOwner", song.channelId)
    }

    @Test
    fun `a playlist video row keeps the uploader channel id`() {
        val song = YouTubeWebParser.parsePlaylist(
            json(
                """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "secondaryContents": {
                        "playlistVideoListRenderer": {
                          "contents": [
                            {"playlistVideoRenderer": {
                              "videoId": "playlistRow1",
                              "title": {"runs": [{"text": "A Video"}]},
                              "shortBylineText": {"runs": [
                                {
                                  "text": "Real Uploader",
                                  "navigationEndpoint": {
                                    "browseEndpoint": {"browseId": "UCplaylistOwner"}
                                  }
                                }
                              ]}
                            }}
                          ]
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        ).songs.single()

        assertEquals("UCplaylistOwner", song.channelId)
    }
}
