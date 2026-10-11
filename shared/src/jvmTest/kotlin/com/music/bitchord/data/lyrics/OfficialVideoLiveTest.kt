package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The moving cover's music video for a track comes from YouTube Music's own
 * Song/Video pairing, not a search — checked against the real service once.
 * Runs only with LIVE_KR_LYRICS=1, like the other live checks.
 */
class OfficialVideoLiveTest {
    private val live = System.getenv("LIVE_KR_LYRICS") == "1"

    @Test
    fun payphoneTrackPairsWithItsOfficialVideo() {
        if (!live) return
        runBlocking {
            val response = Innertube.searchAnonymous("Payphone Maroon 5", SearchFilter.SONGS.params)
            val track = InnertubeParser.parseSearchPage(response, includeVideos = false).rows
                .filterIsInstance<SearchResult.Track>()
                .map { it.song }
                .first { it.title.contains("Payphone", ignoreCase = true) }
            val video = YtMusicRepository.officialVideo(track.videoId)
            println("LOOKUP_MS counterpart:${track.title} ${track.videoId} -> $video")
            assertNotNull(video)
            assertTrue(video != track.videoId)
        }
    }
}
