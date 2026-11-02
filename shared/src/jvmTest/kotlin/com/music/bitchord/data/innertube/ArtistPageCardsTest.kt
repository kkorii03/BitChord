package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.detailBrowseType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cards on an artist page the 1.9 merge changed.
 *
 * An artist card carries its own ARTIST page type, because YouTube's library
 * feed sometimes gives an artist an id that is not a `UC…` channel. The merge
 * dropped that flag, so such a card opened as an album. And the Videos shelf
 * kept only cards whose widescreen art was detected; a video with a square
 * still was dropped even though it can still be played.
 */
class ArtistPageCardsTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun page(carousel: String) = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [ $carousel ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `an artist card without a UC id still opens the artist`() {
        val parsed = InnertubeParser.parseArtistPage(
            json(
                page(
                    """
                    {
                      "musicCarouselShelfRenderer": {
                        "header": {
                          "musicCarouselShelfBasicHeaderRenderer": {
                            "title": { "runs": [ { "text": "Fans might also like" } ] }
                          }
                        },
                        "contents": [
                          {
                            "musicTwoRowItemRenderer": {
                              "title": { "runs": [ { "text": "Artist Name" } ] },
                              "subtitle": { "runs": [ { "text": "Artist" } ] },
                              "navigationEndpoint": {
                                "browseEndpoint": {
                                  "browseId": "MPLAUC123",
                                  "browseEndpointContextSupportedConfigs": {
                                    "browseEndpointContextMusicConfig": {
                                      "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                    }
                                  }
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                    """,
                ),
            ),
        )

        val card = parsed.sections.single().items.single()
        assertTrue(card.isArtist)
        assertEquals(BrowseType.ARTIST, card.detailBrowseType())
    }

    @Test
    fun `a videos shelf keeps a playable video with a square still`() {
        val parsed = InnertubeParser.parseArtistPage(
            json(
                page(
                    """
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
                              "title": { "runs": [ { "text": "Square Video" } ] },
                              "subtitle": { "runs": [ { "text": "Artist" } ] },
                              "navigationEndpoint": {
                                "watchEndpoint": { "videoId": "squareVideo1" }
                              },
                              "thumbnailRenderer": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [
                                      {
                                        "url": "https://i.ytimg.com/vi/squareVideo1/default.jpg",
                                        "width": 120,
                                        "height": 120
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
                    """,
                ),
            ),
        )

        val card = parsed.sections.single().items.single()
        assertEquals("squareVideo1", card.videoId)
        assertFalse(card.isVideo)
    }
}
