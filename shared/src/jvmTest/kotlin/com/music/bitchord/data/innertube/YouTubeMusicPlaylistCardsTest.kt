package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Playlist cards from YouTube Music's featured/community filtered search.
 * The row's owner is reduced to the author so the official `RDCLAK` filter can
 * tell YouTube Music's editorial collections from a channel's playlist.
 */
class YouTubeMusicPlaylistCardsTest {

    @Test
    fun `responsive playlist rows keep their owner and square art`() {
        val items = InnertubeParser.parseMusicPlaylistCards(json(MUSIC_SEARCH))

        assertEquals(listOf("VLRDCLAK5uy_official", "VLPLcommunity"), items.map { it.browseId })
        assertEquals("YouTube Music", items[0].subtitle)
        assertEquals("운동 플레이리스트", items[0].title)
        assertEquals("https://lh3.googleusercontent.com/sq=w544", items[0].thumbnailUrl)
        assertEquals("Some Channel", items[1].subtitle)
    }

    @Test
    fun `album and video rows are not playlist cards`() {
        val items = InnertubeParser.parseMusicPlaylistCards(json(MIXED))

        assertEquals(listOf("VLRDCLAK5uy_two_row"), items.map { it.browseId })
        assertEquals("YouTube Music", items.single().subtitle)
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val MUSIC_SEARCH = """
            {"contents":{"sectionListRenderer":{"contents":[
              {"musicShelfRenderer":{"contents":[
                {"musicResponsiveListItemRenderer":{
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"운동 플레이리스트"}
                    ]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Playlist"},{"text":" • "},{"text":"YouTube Music"}
                    ]}}}
                  ],
                  "navigationEndpoint":{"browseEndpoint":{
                    "browseId":"VLRDCLAK5uy_official",
                    "browseEndpointContextSupportedConfigs":{
                      "browseEndpointContextMusicConfig":{
                        "pageType":"MUSIC_PAGE_TYPE_PLAYLIST"
                      }
                    }
                  }},
                  "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                    {"url":"https://lh3.googleusercontent.com/sq=w120"},
                    {"url":"https://lh3.googleusercontent.com/sq=w544"}
                  ]}}}
                }},
                {"musicResponsiveListItemRenderer":{
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Community Mix"}
                    ]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Playlist"},{"text":" • "},{"text":"Some Channel"}
                    ]}}}
                  ],
                  "navigationEndpoint":{"browseEndpoint":{
                    "browseId":"VLPLcommunity",
                    "browseEndpointContextSupportedConfigs":{
                      "browseEndpointContextMusicConfig":{
                        "pageType":"MUSIC_PAGE_TYPE_PLAYLIST"
                      }
                    }
                  }}
                }}
              ]}}
            ]}}}
        """

        const val MIXED = """
            {"contents":{"sectionListRenderer":{"contents":[
              {"musicResponsiveListItemRenderer":{
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                    {"text":"An Album"}
                  ]}}}
                ],
                "navigationEndpoint":{"browseEndpoint":{
                  "browseId":"MPREb_album",
                  "browseEndpointContextSupportedConfigs":{
                    "browseEndpointContextMusicConfig":{
                      "pageType":"MUSIC_PAGE_TYPE_ALBUM"
                    }
                  }
                }}
              }},
              {"musicTwoRowItemRenderer":{
                "title":{"runs":[{"text":"Two Row Playlist"}]},
                "subtitle":{"runs":[{"text":"YouTube Music"}]},
                "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                  {"url":"https://lh3.googleusercontent.com/sq=w120"}
                ]}}},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDCLAK5uy_two_row"}}
              }}
            ]}}}
        """
    }
}
