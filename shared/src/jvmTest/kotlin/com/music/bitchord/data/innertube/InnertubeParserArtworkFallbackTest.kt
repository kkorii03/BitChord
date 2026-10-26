package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rows that arrive without a `thumbnail` renderer still name their video id,
 * so the parser is the one place that can fill the still in for every screen.
 */
class InnertubeParserArtworkFallbackTest {

    @Test
    fun `playlist rows without a thumbnail use the video still`() {
        val page = InnertubeParser.parsePlaylistShelf(
            json(
                """
                {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
                  "musicPlaylistShelfRenderer":{"contents":[
                    {"musicResponsiveListItemRenderer":{
                      "playlistItemData":{"videoId":"noArtTrack1"},
                      "flexColumns":[
                        {"musicResponsiveListItemFlexColumnRenderer":{
                          "text":{"runs":[{"text":"No Art Track"}]}
                        }},
                        {"musicResponsiveListItemFlexColumnRenderer":{
                          "text":{"runs":[{"text":"Artist"}]}
                        }}
                      ]
                    }},
                    {"musicResponsiveListItemRenderer":{
                      "playlistItemData":{"videoId":"withArtTrck"},
                      "flexColumns":[
                        {"musicResponsiveListItemFlexColumnRenderer":{
                          "text":{"runs":[{"text":"Has Art"}]}
                        }},
                        {"musicResponsiveListItemFlexColumnRenderer":{
                          "text":{"runs":[{"text":"Artist"}]}
                        }}
                      ],
                      "thumbnail":{"musicThumbnailRenderer":{
                        "thumbnail":{"thumbnails":[
                          {"url":"https://i.ytimg.com/vi/withArtTrck/hq.jpg"}
                        ]}
                      }}
                    }}
                  ]}
                }}}}
                """,
            ),
        )

        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/noArtTrack1/hqdefault.jpg",
                "https://i.ytimg.com/vi/withArtTrck/hq.jpg",
            ),
            page?.songs?.map { it.thumbnailUrl },
        )
    }

    @Test
    fun `lockup rows without content art use the video still`() {
        val page = InnertubeParser.parsePlaylistShelf(
            json(
                """
                {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
                  "musicPlaylistShelfRenderer":{"contents":[
                    {"lockupViewModel":{
                      "contentId":"lockupNoArt",
                      "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                      "metadata":{"lockupMetadataViewModel":{
                        "title":{"content":"Lockup Row"}
                      }}
                    }}
                  ]}
                }}}}
                """,
            ),
        )

        assertEquals(
            "https://i.ytimg.com/vi/lockupNoArt/hqdefault.jpg",
            page?.songs?.single()?.thumbnailUrl,
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
