package com.music.bitchord.data.innertube

import com.music.bitchord.data.cover.mediaSessionArtworkAt
import com.music.bitchord.data.cover.videoThumbnailCandidates
import com.music.bitchord.data.cover.videoThumbnailUrl
import com.music.bitchord.data.cover.youtubeStillFallback
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.withVideoThumbnailFallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A song opened from an external `watch?v=` link is read off the same `next`
 * queue as everything else, so the still has to survive every shape that
 * response uses — and when it does not, the video id must still be enough to
 * draw one.
 */
class WatchQueueArtworkFallbackTest {

    private val videoId = "a2F_JllYzZc"
    private val hqDefault = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    private val maxRes = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"

    @Test
    fun `a video queue row keeps the still wrapped by the response`() {
        val song = InnertubeParser.parseWatchQueue(
            json(
                """
                {"contents":{"playlistPanelVideoWrapperRenderer":{
                  "primaryRenderer":{"playlistPanelVideoRenderer":{
                    "videoId":"$videoId",
                    "title":{"runs":[{"text":"New Rules (cover)"}]},
                    "longBylineText":{"runs":[{"text":"오화요"}]},
                    "lengthText":{"runs":[{"text":"3:20"}]},
                    "musicVideoType":"MUSIC_VIDEO_TYPE_UGC",
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                      {"url":"https://i.ytimg.com/vi/$videoId/sddefault.jpg"}
                    ]}}}
                  }}
                }}}
                """,
            ),
        ).single()

        assertEquals("https://i.ytimg.com/vi/$videoId/sddefault.jpg", song.thumbnailUrl)
        assertTrue(song.isVideo)
    }

    @Test
    fun `a video queue row without any still falls back to the video id`() {
        val song = InnertubeParser.parseWatchQueue(
            json(
                """
                {"contents":{"playlistPanelVideoRenderer":{
                  "videoId":"$videoId",
                  "title":{"runs":[{"text":"New Rules (cover)"}]},
                  "longBylineText":{"runs":[{"text":"오화요"}]},
                  "musicVideoType":"MUSIC_VIDEO_TYPE_UGC"
                }}}
                """,
            ),
        ).single()

        assertEquals(hqDefault, song.thumbnailUrl)
    }

    @Test
    fun `the safe net starts at maxres and always has hqdefault`() {
        assertEquals(listOf(maxRes, hqDefault), videoThumbnailCandidates(videoId))
        assertEquals(hqDefault, videoThumbnailUrl(videoId))
        assertTrue(videoThumbnailCandidates("too-short").isEmpty())

        val blank = Song(videoId = videoId, title = "New Rules (cover)", artist = "오화요", thumbnailUrl = null)
        assertEquals(hqDefault, blank.withVideoThumbnailFallback().thumbnailUrl)
    }

    @Test
    fun `a failed large still retries the one every upload has`() {
        assertEquals(hqDefault, youtubeStillFallback(maxRes))
        assertEquals(hqDefault, youtubeStillFallback("https://i.ytimg.com/vi/$videoId/hq720.jpg"))
        assertNull(youtubeStillFallback(hqDefault))
        assertNull(youtubeStillFallback("https://example.com/cover.jpg"))
    }

    @Test
    fun `media session artwork never rewrites a legacy still to a missing hq720`() {
        assertEquals(hqDefault, hqDefault.mediaSessionArtworkAt(544))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
