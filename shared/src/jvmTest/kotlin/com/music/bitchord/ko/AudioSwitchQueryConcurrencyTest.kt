package com.music.bitchord.ko

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** The query pass must not queue a later search behind a slow first one. */
class AudioSwitchQueryConcurrencyTest {

    @Test
    fun `a later query answers while the first is still waiting`() = runBlocking {
        val video = Song(
            videoId = "slow-first-video",
            title = "Chocolate",
            artist = "The 1975",
            thumbnailUrl = null,
            durationText = "3:47",
            isVideo = true,
        )
        val official = Song(
            videoId = "official-fast",
            title = "Chocolate",
            artist = "The 1975",
            thumbnailUrl = null,
            durationText = "3:44",
        )
        val queries = TrackMatcher.audioSwitchQueries(video)
        assertEquals(2, queries.size)
        val secondStarted = CompletableDeferred<Unit>()

        val result = withTimeout(2_000) {
            KoAudioSwitch.resolveAudioUncached(video) { query ->
                if (query == queries.first()) {
                    secondStarted.await()
                    Result.success(emptyList())
                } else {
                    secondStarted.complete(Unit)
                    Result.success(listOf(SearchResult.Track(official)))
                }
            }
        }

        assertEquals("official-fast", result.videoId)
    }
}
