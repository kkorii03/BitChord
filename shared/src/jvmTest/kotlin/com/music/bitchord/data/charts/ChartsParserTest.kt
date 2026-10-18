package com.music.bitchord.data.charts

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The chart response shape was measured only at the list level, so the parser
 * finds each entry's fields by key name anywhere inside it. This fixture puts
 * `trackViews` under an arbitrary wrapper and moves several fields one level
 * deeper to keep that contract honest.
 */
class ChartsParserTest {

    @Test
    fun `tracks are found anywhere and keep their own thumbnail`() {
        val entries = ChartsParser.parse(json(FIXTURE), ChartType.TRACKS)

        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals("첫 곡", first.title)
        assertEquals("vid-a", first.videoId)
        assertEquals("가수 A, 가수 B", first.subtitle)
        assertEquals("https://i.ytimg.com/vi/vid-a/hqdefault.jpg", first.thumbnailUrl)
        assertNull(first.browseId)

        val second = entries[1]
        assertEquals("둘째 곡", second.title)
        assertEquals("vid-b", second.videoId)
        assertEquals("가수 C", second.subtitle)
        assertEquals("https://example.test/b.jpg", second.thumbnailUrl)
    }

    @Test
    fun `videos use the video list and are playable by video id`() {
        val entries = ChartsParser.parse(json(FIXTURE), ChartType.VIDEOS)

        assertEquals(1, entries.size)
        assertEquals("뮤직비디오 A", entries.single().title)
        assertEquals("vid-mv", entries.single().videoId)
        assertEquals("가수 A", entries.single().subtitle)
    }

    @Test
    fun `artists open by channel or browse id and are not songs`() {
        val entries = ChartsParser.parse(json(FIXTURE), ChartType.ARTISTS)

        assertEquals(2, entries.size)
        assertEquals("가수 A", entries[0].title)
        assertEquals("UCartistA", entries[0].browseId)
        assertEquals("구독자 1.2M", entries[0].subtitle)
        assertNull(entries[0].videoId)

        assertEquals("가수 X", entries[1].title)
        assertEquals("UCartistX", entries[1].browseId)
        assertNull(entries[1].videoId)
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    private companion object {
        val FIXTURE = """
            {
              "contents": {
                "wrapper": {
                  "trackViews": [
                    {
                      "name": "첫 곡",
                      "encryptedVideoId": "vid-a",
                      "artists": [{"name": "가수 A"}, {"name": "가수 B"}],
                      "thumbnail": {
                        "thumbnails": [
                          {"url": "https://i.ytimg.com/vi/vid-a/default.jpg", "width": 120, "height": 90},
                          {"url": "https://i.ytimg.com/vi/vid-a/hqdefault.jpg", "width": 480, "height": 360}
                        ]
                      }
                    },
                    {
                      "wrapper": {
                        "name": "둘째 곡",
                        "encryptedVideoId": "vid-b",
                        "artistName": "가수 C",
                        "image": {"sources": [{"url": "https://example.test/b.jpg"}]}
                      }
                    },
                    {
                      "encryptedVideoId": "no-title"
                    }
                  ]
                }
              },
              "elsewhere": {
                "videoViews": [
                  {
                    "name": "뮤직비디오 A",
                    "encryptedVideoId": "vid-mv",
                    "artists": [{"name": "가수 A"}],
                    "thumbnail": {"thumbnails": [{"url": "https://example.test/mv.jpg"}]}
                  }
                ],
                "artistViews": [
                  {
                    "wrapper": {
                      "name": "가수 A",
                      "channelId": "UCartistA",
                      "subtitle": "구독자 1.2M",
                      "thumbnail": {"thumbnails": [{"url": "https://example.test/a.jpg"}]}
                    }
                  },
                  {
                    "name": "가수 X",
                    "browseId": "UCartistX",
                    "thumbnail": {"thumbnails": [{"url": "https://example.test/x.jpg"}]}
                  }
                ]
              }
            }
        """.trimIndent()
    }
}
