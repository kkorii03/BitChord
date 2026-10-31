package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchQueueVideoTypeTest {

    private fun watchQueue(videoId: String, videoType: String): String = """
        {
          "contents": [
            {
              "playlistPanelVideoRenderer": {
                "videoId": "$videoId",
                "title": { "runs": [ { "text": "A Song" } ] },
                "longBylineText": { "runs": [ { "text": "An Artist" } ] },
                "navigationEndpoint": {
                  "watchEndpoint": {
                    "videoId": "$videoId",
                    "watchEndpointMusicSupportedConfigs": {
                      "watchEndpointMusicConfig": { "musicVideoType": "$videoType" }
                    }
                  }
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an OMV watch row is a video`() {
        val song = InnertubeParser.parseWatchQueue(
            Json.parseToJsonElement(watchQueue("qyh6mYIJG2Y", "MUSIC_VIDEO_TYPE_OMV")),
        ).single()

        assertEquals("qyh6mYIJG2Y", song.videoId)
        assertTrue(song.isVideo)
    }

    @Test
    fun `an ATV watch row is the catalogue song`() {
        val song = InnertubeParser.parseWatchQueue(
            Json.parseToJsonElement(watchQueue("xz868t23pb4", "MUSIC_VIDEO_TYPE_ATV")),
        ).single()

        assertEquals("xz868t23pb4", song.videoId)
        assertFalse(song.isVideo)
    }
}
