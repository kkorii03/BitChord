package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.ShelfItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `an official video's counterpart is the catalogue song row`() {
        val root = json(COUNTERPART)

        val song = assertNotNull(InnertubeParser.parseCounterpartSong(root, "YQHsXMglC9A"))
        assertEquals("ATV00000001", song.videoId)
        assertEquals("Hello", song.title)
        assertEquals("Adele", song.artist)
        assertEquals("4:55", song.durationText)
        assertFalse(song.isVideo)
        assertTrue(song.isVideoOrigin)

        assertEquals("ATV00000001", InnertubeParser.parseCounterpart(root, "YQHsXMglC9A"))
        assertNull(InnertubeParser.parseCounterpart(root, "SOMEOTHERID"))
        assertNull(InnertubeParser.parseCounterpartSong(root, "SOMEOTHERID"))
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
        // No dimensions in the row, so it is left as a song rather than guessed.
        assertFalse(song.isVideo)
    }

    @Test
    fun `a wide playlist upload is a video and square art stays a song`() {
        val songs = YouTubeWebParser.parsePlaylist(json(PLAYLIST_ROWS)).songs

        assertEquals(listOf("videoRow001", "artTrack001"), songs.map { it.videoId })
        assertTrue(songs[0].isVideo)
        assertTrue(songs[0].isVideoOrigin)
        assertFalse(songs[1].isVideo)
        assertFalse(songs[1].isVideoOrigin)
    }

    @Test
    fun `a wide lockup upload is a video`() {
        val song = YouTubeWebParser.parsePlaylist(json(PLAYLIST_LOCKUP_WIDE)).songs.single()

        assertTrue(song.isVideo)
        assertTrue(song.isVideoOrigin)
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

    @Test
    fun `a protocol relative lockup thumbnail becomes an absolute webp url`() {
        val item = parseShelf(
            """
            {"lockupViewModel":{
              "contentId":"PLrelative",
              "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"thumbnailViewModel":{"image":{"sources":[
                {"url":"//i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.webp?sqp=abc&rs=1","width":480,"height":360}
              ]}}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Relative Cover"}}}
            }}
            """,
        ).single()

        assertEquals(
            "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.webp?sqp=abc&rs=1",
            item.thumbnailUrl,
        )
    }

    @Test
    fun `collection lockups read primary, stacked and legacy thumbnails`() {
        val items = parseShelf(
            """
            {"lockupViewModel":{
              "contentId":"PLcollection",
              "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{
                "thumbnailViewModel":{"image":{"sources":[
                  {"url":"https://i.ytimg.com/vi/bbbbbbbbbbb/hqdefault.jpg"}
                ]}}
              }}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Collection Cover"}}}
            }}
            """,
            """
            {"lockupViewModel":{
              "contentId":"PLstacked",
              "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"collectionThumbnailViewModel":{"stackedThumbnailViewModel":{
                "primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[
                  {"url":"https://i.ytimg.com/vi/ccccccccccc/hqdefault.jpg"}
                ]}}}
              }}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Stacked Cover"}}}
            }}
            """,
            """
            {"lockupViewModel":{
              "contentId":"PLlegacy",
              "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{
                "thumbnail":{"thumbnails":[
                  {"url":"https://i.ytimg.com/vi/eeeeeeeeeee/hqdefault.jpg"}
                ]}
              }}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Legacy Cover"}}}
            }}
            """,
        )

        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/bbbbbbbbbbb/hqdefault.jpg",
                "https://i.ytimg.com/vi/ccccccccccc/hqdefault.jpg",
                "https://i.ytimg.com/vi/eeeeeeeeeee/hqdefault.jpg",
            ),
            items.map { it.thumbnailUrl },
        )
    }

    @Test
    fun `a source without a url does not hide the valid image`() {
        val item = parseShelf(
            """
            {"lockupViewModel":{
              "contentId":"PLsources",
              "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"thumbnailViewModel":{"image":{"sources":[
                {"url":"https://i.ytimg.com/vi/fffffffffff/hqdefault.jpg","width":480,"height":360},
                {"width":120,"height":90}
              ]}}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Source Cover"}}}
            }}
            """,
        ).single()

        assertEquals("https://i.ytimg.com/vi/fffffffffff/hqdefault.jpg", item.thumbnailUrl)
    }

    @Test
    fun `a playlist without an image falls back to its first video`() {
        val item = parseShelf(
            """
            {"lockupViewModel":{
              "contentId":"PLnoimage",
              "contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"No Cover"}}},
              "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{
                "watchEndpoint":{"videoId":"ddddddddddd","playlistId":"PLnoimage"}
              }}}}
            }}
            """,
        ).single()

        assertEquals("https://i.ytimg.com/vi/ddddddddddd/hqdefault.jpg", item.thumbnailUrl)
    }

    @Test
    fun `an album lockup keeps its image and browse id`() {
        val item = parseShelf(
            """
            {"lockupViewModel":{
              "contentId":"OLAK5uy_album",
              "contentType":"LOCKUP_CONTENT_TYPE_ALBUM",
              "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{
                "thumbnailViewModel":{"image":{"sources":[
                  {"url":"https://i.ytimg.com/vi/ggggggggggg/hqdefault.jpg"}
                ]}}
              }}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"An Album"}}}
            }}
            """,
        ).single()

        assertEquals("https://i.ytimg.com/vi/ggggggggggg/hqdefault.jpg", item.thumbnailUrl)
        assertEquals("VLOLAK5uy_album", item.browseId)
    }

    @Test
    fun `playlist rows without a thumbnail use the video still and keep real art`() {
        val page = YouTubeWebParser.parsePlaylist(
            json(
                """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "secondaryContents": {
                        "playlistVideoListRenderer": {
                          "contents": [
                            {"playlistVideoRenderer": {
                              "videoId": "noArtRow001",
                              "title": {"runs": [{"text": "No Art Row"}]},
                              "shortBylineText": {"runs": [{"text": "Artist"}]}
                            }},
                            {"playlistVideoRenderer": {
                              "videoId": "withArtRow1",
                              "title": {"runs": [{"text": "Has Art"}]},
                              "shortBylineText": {"runs": [{"text": "Artist"}]},
                              "thumbnail": {"thumbnails": [
                                {"url": "https://i.ytimg.com/vi/withArtRow1/hq.jpg"}
                              ]}
                            }}
                          ]
                        }
                      },
                      "tabs": [
                        {"tabRenderer": {"content": {"sectionListRenderer": {"contents": [
                          {"playlistSidebarPrimaryInfoRenderer": {
                            "title": {"runs": [{"text": "No Header Art"}]}
                          }}
                        ]}}}}
                      ]
                    }
                  }
                }
                """,
            ),
        )

        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/noArtRow001/hqdefault.jpg",
                "https://i.ytimg.com/vi/withArtRow1/hq.jpg",
            ),
            page.songs.map { it.thumbnailUrl },
        )
        assertEquals("No Header Art", page.header?.title)
        assertEquals(
            "https://i.ytimg.com/vi/noArtRow001/hqdefault.jpg",
            page.header?.thumbnailUrl,
        )
    }

    @Test
    fun `a video lockup without an image keeps the video still`() {
        val song = YouTubeWebParser.parsePlaylist(
            json(
                """
                {
                  "contents": {
                    "twoColumnBrowseResultsRenderer": {
                      "secondaryContents": {
                        "playlistVideoListRenderer": {
                          "contents": [
                            {"lockupViewModel": {
                              "contentId": "videoOnly01",
                              "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                              "metadata": {"lockupMetadataViewModel": {
                                "title": {"content": "야화 - 안예은 · 오화요 커버"}
                              }}
                            }}
                          ]
                        }
                      }
                    }
                  }
                }
                """,
            ),
        ).songs.single()

        assertEquals("야화 - 안예은 · 오화요 커버", song.title)
        assertEquals("https://i.ytimg.com/vi/videoOnly01/hqdefault.jpg", song.thumbnailUrl)
    }

    private fun parseShelf(vararg lockups: String): List<ShelfItem> {
        val items = lockups.joinToString(",") { """{"richItemRenderer":{"content":$it}}""" }
        val root = json(
            """
            {"contents":{"richGridRenderer":{"contents":[{"richShelfRenderer":{
              "title":{"runs":[{"text":"오늘의 핫리스트"}]},
              "content":{"richGridRenderer":{"contents":[$items]}}
            }}]}}}
            """,
        )
        return YouTubeWebParser.parseHome(root, musicOnly = false).shelves.single().items
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

        /** Trimmed from a music.youtube.com `next` queue with an OMV row. */
        const val COUNTERPART = """
            {"contents":{"contents":[
              {"playlistPanelVideoWrapperRenderer":{
                "primaryRenderer":{"playlistPanelVideoRenderer":{
                  "videoId":"YQHsXMglC9A",
                  "title":{"runs":[{"text":"Hello (Official Music Video)"}]},
                  "longBylineText":{"runs":[{"text":"Adele"},{"text":" • "},{"text":"417M views"}]},
                  "lengthText":{"runs":[{"text":"6:07"}]},
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/YQHsXMglC9A/hq.jpg"}]},
                  "navigationEndpoint":{"watchEndpoint":{
                    "videoId":"YQHsXMglC9A",
                    "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
                      "musicVideoType":"MUSIC_VIDEO_TYPE_OMV"
                    }}
                  }}
                }},
                "counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":{
                  "videoId":"ATV00000001",
                  "title":{"runs":[{"text":"Hello"}]},
                  "longBylineText":{"runs":[{"text":"Adele"}]},
                  "lengthText":{"runs":[{"text":"4:55"}]},
                  "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/ATV00000001/hq.jpg"}]},
                  "navigationEndpoint":{"watchEndpoint":{
                    "videoId":"ATV00000001",
                    "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
                      "musicVideoType":"MUSIC_VIDEO_TYPE_ATV"
                    }}
                  }}
                }}}]
              }}
            ]}}
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

        const val PLAYLIST_ROWS = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"playlistVideoRenderer":{
                  "videoId":"videoRow001",
                  "title":{"runs":[{"text":"Official Video"}]},
                  "shortBylineText":{"runs":[{"text":"Artist"}]},
                  "lengthText":{"simpleText":"3:20"},
                  "thumbnail":{"thumbnails":[
                    {"url":"https://i.ytimg.com/vi/videoRow001/hq.jpg","width":336,"height":188}
                  ]}
                }},
                {"playlistVideoRenderer":{
                  "videoId":"artTrack001",
                  "title":{"runs":[{"text":"Catalogue Track"}]},
                  "shortBylineText":{"runs":[{"text":"Artist - Topic"}]},
                  "lengthText":{"simpleText":"3:10"},
                  "thumbnail":{"thumbnails":[
                    {"url":"https://i.ytimg.com/vi/artTrack001/hq.jpg","width":544,"height":544}
                  ]}
                }}
              ]}}
            ]}}}}]}}}
        """

        const val PLAYLIST_LOCKUP_WIDE = """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"lockupViewModel":{
                  "contentId":"lockupVid01",
                  "contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
                  "contentImage":{"thumbnailViewModel":{"image":{"sources":[
                    {"url":"https://i.ytimg.com/vi/lockupVid01/hq.jpg","width":336,"height":188}
                  ]}}},
                  "metadata":{"lockupMetadataViewModel":{
                    "title":{"content":"Wide Lockup"},
                    "metadata":{"contentMetadataViewModel":{"metadataRows":[
                      {"metadataParts":[{"text":{"content":"Artist"}}]}
                    ]}}
                  }}
                }}
              ]}}
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
