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
        // The kind is kept, not just the video flag: an official video is the
        // release of a catalogue song, which is what music-only acts on.
        assertEquals("MUSIC_VIDEO_TYPE_OMV", song.musicVideoType)
    }

    @Test
    fun `a UGC watch row is a video that exists only as one`() {
        val song = InnertubeParser.parseWatchQueue(
            Json.parseToJsonElement(watchQueue("ugc00000001", "MUSIC_VIDEO_TYPE_UGC")),
        ).single()

        assertTrue(song.isVideo)
        assertEquals("MUSIC_VIDEO_TYPE_UGC", song.musicVideoType)
    }

    @Test
    fun `an ATV watch row is the catalogue song`() {
        val song = InnertubeParser.parseWatchQueue(
            Json.parseToJsonElement(watchQueue("xz868t23pb4", "MUSIC_VIDEO_TYPE_ATV")),
        ).single()

        assertEquals("xz868t23pb4", song.videoId)
        assertFalse(song.isVideo)
        assertEquals("MUSIC_VIDEO_TYPE_ATV", song.musicVideoType)
    }
}
