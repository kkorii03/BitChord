package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The web renderers the fallback reads. Radio rows and playlist cards are
 * trimmed from real anonymous responses fetched on 2026-10-18; the rich-shelf
 * fixture is the same shape the Music channel answers with.
 */
class YouTubeWebParserTest {

    @Test
    fun `radio parses playlistPanelVideoRenderer rows`() {
        val page = YouTubeWebParser.parseRadio(json(RADIO))

        assertEquals(2, page.songs.size)
        val first = page.songs.first()
        assertEquals("dQw4w9WgXcQ", first.videoId)
        assertEquals("Rick Astley - Never Gonna Give You Up", first.title)
        assertEquals("Rick Astley", first.artist)
        assertEquals("3:34", first.durationText)
        assertEquals("56B44F6D10557CC6", first.setVideoId)
        assertEquals("Mix - Rick Astley", page.header?.title)
        assertEquals("YouTube", page.header?.subtitle)
    }

    @Test
    fun `a playlist page reads lockup rows and its continuation`() {
        val page = YouTubeWebParser.parsePlaylist(json(PLAYLIST))

        assertEquals(1, page.songs.size)
        val song = page.songs.single()
        assertEquals("RTWhvp_OD6s", song.videoId)
        assertEquals("Where You Are", song.title)
        assertEquals("DisneyMusicVEVO", song.artist)
        assertEquals("3:15", song.durationText)
        assertEquals("TOKEN123", page.continuation)
    }

    @Test
    fun `home shelves keep only music rows when asked`() {
        val all = YouTubeWebParser.parseHome(json(CHANNEL), musicOnly = false)
        assertEquals(1, all.shelves.size)
        assertEquals("New & Trending", all.shelves.single().title)
        assertEquals(3, all.shelves.single().items.size)

        val music = YouTubeWebParser.parseHome(json(CHANNEL), musicOnly = true)
        assertEquals(2, music.shelves.single().items.size)
        assertTrue(music.shelves.single().items.any { it.videoId == "musicVideo1" })
        assertTrue(music.shelves.single().items.any { it.browseId == "RDmix123" })
    }

    @Test
    fun `history keeps music rows and falls back to all rows`() {
        val songs = YouTubeWebParser.parseHistory(json(CHANNEL))
        assertEquals(listOf("musicVideo1"), songs.map { it.videoId })
    }

    @Test
    fun `library items expose only browsable playlist cards`() {
        val items = YouTubeWebParser.parseLibraryItems(json(LIBRARY))
        assertNotNull(items.firstOrNull { it.browseId == "VLPLown123" })
        assertTrue(items.none { it.videoId != null })
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val RADIO = """
            {"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{
              "titleText":{"simpleText":"Mix - Rick Astley"},
              "ownerName":{"simpleText":"YouTube"},
              "contents":[
                {"playlistPanelVideoRenderer":{
                  "videoId":"dQw4w9WgXcQ",
                  "title":{"runs":[{"text":"Rick Astley - Never Gonna Give You Up"}]},
                  "shortBylineText":{"runs":[{"text":"Rick Astley"}]},
                  "lengthText":{"simpleText":"3:34"},
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg"}]},
                  "playlistSetVideoId":"56B44F6D10557CC6"
                }},
                {"playlistPanelVideoRenderer":{
                  "videoId":"yPYZpwSpKmA",
                  "title":{"runs":[{"text":"Together Forever"}]},
                  "longBylineText":{"runs":[{"text":"Rick Astley"}]},
                  "lengthText":{"simpleText":"3:24"},
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/yPYZpwSpKmA/hq.jpg"}]}
                }}
              ]
            }}}}}
        """

        const val PLAYLIST = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"lockupViewModel":{
                  "contentId":"RTWhvp_OD6s",
                  "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                  "contentImage":{"thumbnailViewModel":{
                    "image":{"sources":[{"url":"https://i.ytimg.com/vi/RTWhvp_OD6s/hq.jpg"}]},
                    "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[
                      {"thumbnailBadgeViewModel":{
                        "text":"3:15",
                        "icon":{"sources":[{"clientResource":{"imageName":"MUSIC"}}]}
                      }}
                    ]}}]
                  }},
                  "metadata":{"lockupMetadataViewModel":{
                    "title":{"content":"Where You Are"},
                    "metadata":{"contentMetadataViewModel":{"metadataRows":[
                      {"metadataParts":[{"text":{"content":"DisneyMusicVEVO"}}]},
                      {"metadataParts":[{"text":{"content":"1M views"}},{"text":{"content":"2 weeks ago"}}]}
                    ]}}
                  }}
                }}
              ]}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOKEN123"}}}}
            ]}}}}]}}}
        """

        const val CHANNEL = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
              {"richShelfRenderer":{
                "title":{"runs":[{"text":"New & Trending"}]},
                "content":{"richGridRenderer":{"contents":[
                  {"richItemRenderer":{"content":{"lockupViewModel":{
                    "contentId":"musicVideo1",
                    "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                    "contentImage":{"thumbnailViewModel":{
                      "image":{"sources":[{"url":"https://i.ytimg.com/vi/musicVideo1/hq.jpg"}]},
                      "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[
                        {"thumbnailBadgeViewModel":{"text":"3:15","icon":{"sources":[{"clientResource":{"imageName":"MUSIC"}}]}}}
                      ]}}]
                    }},
                    "metadata":{"lockupMetadataViewModel":{
                      "title":{"content":"A Song"},
                      "metadata":{"contentMetadataViewModel":{"metadataRows":[
                        {"metadataParts":[{"text":{"content":"Artist - Topic"}}]}
                      ]}}
                    }}
                  }}}},
                  {"richItemRenderer":{"content":{"lockupViewModel":{
                    "contentId":"RDmix123",
                    "contentType":"LOCKUP_CONTENT_TYPE_STATION",
                    "contentImage":{"thumbnailViewModel":{
                      "image":{"sources":[{"url":"https://i.ytimg.com/vi/station1/hq.jpg"}]}
                    }},
                    "metadata":{"lockupMetadataViewModel":{
                      "title":{"content":"Mix - Artist"},
                      "metadata":{"contentMetadataViewModel":{"metadataRows":[
                        {"metadataParts":[{"text":{"content":"YouTube"}}]}
                      ]}}
                    }}
                  }}}},
                  {"richItemRenderer":{"content":{"lockupViewModel":{
                    "contentId":"vlog1",
                    "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                    "contentImage":{"thumbnailViewModel":{
                      "image":{"sources":[{"url":"https://i.ytimg.com/vi/vlog1/hq.jpg"}]}
                    }},
                    "metadata":{"lockupMetadataViewModel":{
                      "title":{"content":"A Vlog"},
                      "metadata":{"contentMetadataViewModel":{"metadataRows":[
                        {"metadataParts":[{"text":{"content":"Some Vlogger"}}]}
                      ]}}
                    }}
                  }}}}
                ]}}
              }}
            ]}}}}]}}}
        """

        const val LIBRARY = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"lockupViewModel":{
                  "contentId":"PLown123",
                  "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                  "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                    "image":{"sources":[{"url":"https://i.ytimg.com/vi/pl/hq.jpg"}]}
                  }}}},
                  "metadata":{"lockupMetadataViewModel":{
                    "title":{"content":"My Playlist"},
                    "metadata":{"contentMetadataViewModel":{"metadataRows":[
                      {"metadataParts":[{"text":{"content":"Me"}},{"text":{"content":"Playlist"}}]}
                    ]}}
                  }}
                }}
              ]}}
            ]}}}}]}}}
        """
    }
}
