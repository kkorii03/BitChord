package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 1.9 merge dropped the `pageType`-derived artist flag from two-row cards.
 * Only ids starting with UC still looked like artists, so an artist card with
 * another browse id was drawn as an album/playlist.
 */
class HomeArtistCardTest {

    private fun homeRow(pageType: String, browseId: String): String = """
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
                                  "title": { "runs": [ { "text": "Artists" } ] }
                                }
                              },
                              "contents": [
                                {
                                  "musicTwoRowItemRenderer": {
                                    "title": { "runs": [ { "text": "An Artist" } ] },
                                    "subtitle": { "runs": [ { "text": "Artist" } ] },
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "$browseId",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": {
                                            "pageType": "$pageType"
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
          }
        }
    """.trimIndent()

    private fun card(pageType: String, browseId: String) =
        InnertubeParser.parseHome(Json.parseToJsonElement(homeRow(pageType, browseId)).jsonObject)
            .single().items.single()

    @Test
    fun `an artist card is an artist even without a UC browse id`() {
        assertTrue(card("MUSIC_PAGE_TYPE_ARTIST", "MPREb_artist").isArtist)
    }

    @Test
    fun `an album card is not an artist`() {
        assertFalse(card("MUSIC_PAGE_TYPE_ALBUM", "MPREb_album").isArtist)
    }
}
