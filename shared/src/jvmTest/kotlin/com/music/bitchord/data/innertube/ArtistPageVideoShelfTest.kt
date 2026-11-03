package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `a videos shelf keeps a square-still video card`() {
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

        assertEquals(1, page.sections.size)
        assertEquals("squareVideo1", page.sections.single().items.single().videoId)
    }

    @Test
    fun `an artist card carries the page type that YouTube states`() {
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
                                          "title": { "runs": [ { "text": "Fans might also like" } ] }
                                        }
                                      },
                                      "contents": [
                                        {
                                          "musicTwoRowItemRenderer": {
                                            "title": { "runs": [ { "text": "Another Artist" } ] },
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

        assertEquals(1, page.sections.size)
        assertTrue(page.sections.single().items.single().isArtist)
    }

    @Test
    fun `top songs trust musicVideoType over thumbnail shape`() {
        fun row(videoId: String, title: String, width: Int, height: Int, videoType: String) = """
            {
              "musicResponsiveListItemRenderer": {
                "playlistItemData": { "videoId": "$videoId" },
                "flexColumns": [
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "runs": [ { "text": "$title" } ] }
                    }
                  },
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "runs": [ { "text": "Artist" } ] }
                    }
                  }
                ],
                "thumbnail": {
                  "musicThumbnailRenderer": {
                    "thumbnail": {
                      "thumbnails": [
                        {
                          "url": "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                          "width": $width,
                          "height": $height
                        }
                      ]
                    }
                  }
                },
                "overlay": {
                  "musicItemThumbnailOverlayRenderer": {
                    "content": {
                      "musicPlayButtonRenderer": {
                        "playNavigationEndpoint": {
                          "watchEndpoint": {
                            "videoId": "$videoId",
                            "watchEndpointMusicConfig": { "musicVideoType": "$videoType" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """

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
                                    "musicShelfRenderer": {
                                      "title": { "runs": [ { "text": "Top songs" } ] },
                                      "contents": [
                                        ${row("omvSquare", "Official Video", 120, 120, "MUSIC_VIDEO_TYPE_OMV")},
                                        ${row("atvWide", "Catalogue Song", 480, 270, "MUSIC_VIDEO_TYPE_ATV")}
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

        val songs = page.songs.associateBy { it.videoId }
        assertTrue(songs.getValue("omvSquare").isVideo, "an OMV row stays a video even with square art")
        assertFalse(songs.getValue("atvWide").isVideo, "an ATV row stays the song even with wide art")
    }
}
