package com.music.bitchord.data.innertube

import com.music.bitchord.data.YouTubeDisplayCleaner
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

    /**
     * Phone 11-06: a shared link starts at this watch queue, with no search row
     * behind it, so the uploader's byline read as the artist and the song
     * version search asked for the channel. The same display rule every other
     * row goes through reads the credit the title states.
     */
    @Test
    fun `a shared link's watch row takes the artist its title states`() {
        val json = watchQueue("NPZtKA6343o", "MUSIC_VIDEO_TYPE_OMV")
            .replace("A Song", "ILLIT (아일릿) 'You're Under Arrest' Official MV")
            .replace("An Artist", "HYPE UNIVERSE")

        val row = YouTubeDisplayCleaner.cleanUpload(
            InnertubeParser.parseWatchQueue(Json.parseToJsonElement(json)).single(),
        )

        assertEquals("You're Under Arrest", row.title)
        assertEquals("ILLIT", row.artist)
    }
}
