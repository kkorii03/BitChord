package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Library artist card whose id is not the usual `UC…` must still say it is
 * an artist. The v1.9 merge dropped that flag from the two-row card parser, so
 * the card opened as an album, was drawn square, and its subtitle read as a
 * release.
 */
class LibraryArtistCardTest {

    private fun card(browseId: String, pageType: String) = Json.parseToJsonElement(
        """
        {
          "contents": {
            "musicTwoRowItemRenderer": {
              "title": { "runs": [ { "text": "The Artist" } ] },
              "subtitle": { "runs": [ { "text": "Artist" } ] },
              "navigationEndpoint": {
                "browseEndpoint": {
                  "browseId": "$browseId",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": { "pageType": "$pageType" }
                  }
                }
              },
              "thumbnailRenderer": {
                "musicThumbnailRenderer": {
                  "thumbnail": {
                    "thumbnails": [
                      { "url": "https://example.invalid/artist.jpg", "width": 120, "height": 120 }
                    ]
                  }
                }
              }
            }
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `an artist card with a non-UC id keeps its artist flag`() {
        val item = InnertubeParser.parseLibraryItems(
            card("MPLAUC123", "MUSIC_PAGE_TYPE_ARTIST"),
        ).single()

        assertTrue(item.isArtist)
    }

    @Test
    fun `an album card is not an artist`() {
        val item = InnertubeParser.parseLibraryItems(
            card("MPREb123", "MUSIC_PAGE_TYPE_ALBUM"),
        ).single()

        assertFalse(item.isArtist)
    }
}
