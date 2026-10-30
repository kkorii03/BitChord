package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An artist whose only work is videos is still an artist with a page.
 *
 * The Video shelf used to be discarded before its cards were read, so the
 * page reached the app with neither top songs nor shelves and said "No tracks
 * here". Its cards are content: keep them, with their wide stills.
 */
class ArtistPageVideoShelfTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `an artist with only a videos shelf keeps that shelf`() {
        val page = InnertubeParser.parseArtistPage(
            json(
                """
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
                                    "musicCarouselShelfRenderer": {
                                      "header": {
                                        "musicCarouselShelfBasicHeaderRenderer": {
                                          "title": { "runs": [ { "text": "Videos" } ] }
                                        }
                                      },
                                      "contents": [
                                        {
                                          "musicTwoRowItemRenderer": {
                                            "title": { "runs": [ { "text": "Only a Video" } ] },
                                            "subtitle": { "runs": [ { "text": "Artist • 1M views" } ] },
                                            "navigationEndpoint": {
                                              "watchEndpoint": { "videoId": "videoOnly1" }
                                            },
                                            "thumbnailRenderer": {
                                              "musicThumbnailRenderer": {
                                                "thumbnail": {
                                                  "thumbnails": [
                                                    {
                                                      "url": "https://i.ytimg.com/vi/videoOnly1/hqdefault.jpg",
                                                      "width": 480,
                                                      "height": 360
                                                    }
                                                  ]
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
                  },
                  "header": {
                    "musicImmersiveHeaderRenderer": {
                      "title": { "runs": [ { "text": "Artist" } ] }
                    }
                  }
                }
                """
            ),
        )

        assertTrue(page.songs.isEmpty())
        assertEquals(1, page.sections.size)
        assertEquals("Videos", page.sections.single().title)
        assertEquals("videoOnly1", page.sections.single().items.single().videoId)
        assertTrue(page.sections.single().items.single().isVideo)
    }
}
