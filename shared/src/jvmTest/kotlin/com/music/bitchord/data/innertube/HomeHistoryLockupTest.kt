package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * History pages can list a track as a `lockupViewModel` rather than a
 * responsive row. Recents reads history through [InnertubeParser.collectSongsDeep],
 * so that walk must keep lockup rows too or the shelf silently loses them.
 */
class HomeHistoryLockupTest {

    @Test
    fun `the deep walk keeps a lockup history row`() {
        val page = Json.parseToJsonElement(
            """
            {
              "contents": [
                {
                  "lockupViewModel": {
                    "contentId": "lockupHistory1",
                    "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                    "contentImage": {
                      "thumbnailViewModel": {
                        "image": {
                          "sources": [
                            {
                              "url": "https://i.ytimg.com/vi/lockupHistory1/hq.jpg",
                              "width": 336,
                              "height": 188
                            }
                          ]
                        }
                      }
                    },
                    "metadata": {
                      "lockupMetadataViewModel": {
                        "title": { "content": "History Song" },
                        "metadata": {
                          "contentMetadataViewModel": {
                            "metadataRows": [
                              {
                                "metadataParts": [
                                  { "text": { "content": "History Artist" } }
                                ]
                              }
                            ]
                          }
                        }
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val song = InnertubeParser.collectSongsDeep(page).single()

        assertEquals("lockupHistory1", song.videoId)
        assertEquals("History Song", song.title)
        assertEquals("History Artist", song.artist)
    }
}
