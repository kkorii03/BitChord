package com.music.bitchord.data

import com.music.bitchord.data.innertube.YouTubeWebParser
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.firstArtworkOrStill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A channel playlist can answer with rows and no release cover. The still the
 * video id always names is what keeps both the list and the large sleeve from
 * going blank, so it is filled at the data layer rather than left to one screen.
 */
class PlaylistArtworkFallbackTest {

    @Test
    fun `a stored playlist row without a thumbnail gets the video still`() {
        val stored = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Night Flower (야화) (cover)",
            artist = "오화요",
            thumbnailUrl = null,
        )

        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            StoredSongDisplay.clean(stored).thumbnailUrl,
        )
    }

    @Test
    fun `a blank stored thumbnail is replaced by the video still`() {
        val stored = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Night Flower (야화) (cover)",
            artist = "오화요",
            thumbnailUrl = "   ",
        )

        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            StoredSongDisplay.clean(stored).thumbnailUrl,
        )
    }

    @Test
    fun `the header takes the first real cover before a video still`() {
        val songs = listOf(
            Song(videoId = "noArtTrack1", title = "No Art", artist = "오화요", thumbnailUrl = null),
            Song(
                videoId = "withArtTrck",
                title = "Has Art",
                artist = "오화요",
                thumbnailUrl = "https://i.ytimg.com/vi/withArtTrck/hq.jpg",
            ),
        )

        assertEquals(
            "https://i.ytimg.com/vi/noArtTrack1/hqdefault.jpg",
            songs.firstArtworkOrStill(),
        )
    }

    @Test
    fun `a header falls back through rows to their video stills`() {
        val songs = listOf(
            Song(videoId = "blankArt001", title = "Blank", artist = "오화요", thumbnailUrl = ""),
            Song(videoId = "noArtTrack1", title = "No Art", artist = "오화요", thumbnailUrl = null),
        )

        assertEquals(
            "https://i.ytimg.com/vi/blankArt001/hqdefault.jpg",
            songs.firstArtworkOrStill(),
        )
    }


    @Test
    fun `a youtube playlist header without a cover takes a row's video still`() {
        val root = Json.parseToJsonElement(
            """
            {"contents":{"playlistVideoListRenderer":{"contents":[
              {"playlistVideoRenderer":{
                "videoId":"dQw4w9WgXcQ",
                "title":{"runs":[{"text":"Night Flower (야화) (cover)"}]},
                "shortBylineText":{"runs":[{"text":"오화요"}]},
                "lengthText":{"simpleText":"4:25"}
              }}
            ]}},
            "header":{"pageHeaderRenderer":{"pageTitle":{"simpleText":"오화요 한식모음"}}}}
            """.trimIndent(),
        ).jsonObject

        val page = YouTubeWebParser.parsePlaylist(root)

        assertEquals("오화요 한식모음", page.header?.title)
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            page.header?.thumbnailUrl,
        )
    }

    @Test
    fun `a local row with no YouTube id has no header fallback`() {
        val songs = listOf(
            Song(
                videoId = "content://media/1",
                title = "Local",
                artist = "오화요",
                thumbnailUrl = null,
                localUri = "content://media/1",
            ),
        )

        assertNull(songs.firstArtworkOrStill())
    }
}
