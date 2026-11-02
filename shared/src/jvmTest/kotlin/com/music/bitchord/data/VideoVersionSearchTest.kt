package com.music.bitchord.data

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The video switch keeps its 1.8 guards: never search a blank row, never pick a take. */
class VideoVersionSearchTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = null,
        artistId: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        artistId = artistId,
    )

    private val target = TrackMatcher.Target(
        title = "After LIKE",
        artist = "IVE",
        durationSec = 192,
        artistId = "UC_IVE",
    )

    private val official = song("OFFICIAL001", "After LIKE", "IVE", "3:12", "UC_IVE")

    @Test
    fun `a row without a header is not searched`() {
        assertFalse(YtMusicRepository.videoVersionSearchWanted(song("V0", "", "IVE")))
        assertFalse(YtMusicRepository.videoVersionSearchWanted(song("V1", "After LIKE", "")))
        assertTrue(YtMusicRepository.videoVersionSearchWanted(song("V2", "After LIKE", "IVE")))
    }

    @Test
    fun `a blank row remembers its miss once the pairing answered`() = runBlocking {
        val row = song("BLANKPAIR01", "", "IVE")
        var pairings = 0
        val pairing = {
            pairings++
            YtMusicRepository.VersionPairing(null, answered = true)
        }
        val search: suspend (String) -> Result<List<SearchResult>> = { error("must not search") }

        assertNull(YtMusicRepository.resolveVideo(row, search, pairing))
        assertNull(YtMusicRepository.resolveVideo(row, search, pairing))
        assertEquals(1, pairings)
    }

    @Test
    fun `a blank row keeps retrying when the pairing never answered`() = runBlocking {
        val row = song("BLANKPAIR02", "", "IVE")
        var pairings = 0
        val pairing = {
            pairings++
            YtMusicRepository.VersionPairing(null, answered = false)
        }
        val search: suspend (String) -> Result<List<SearchResult>> = { error("must not search") }

        assertNull(YtMusicRepository.resolveVideo(row, search, pairing))
        assertNull(YtMusicRepository.resolveVideo(row, search, pairing))
        assertEquals(2, pairings)
    }

    @Test
    fun `a failed search does not freeze the miss`() = runBlocking {
        val row = song("FAILSEARCH01", "After LIKE", "IVE")
        val queriesPerPass = TrackMatcher.queries(TrackMatcher.targetOf(row)).size
        var searches = 0
        val pairing = { YtMusicRepository.VersionPairing(null, answered = true) }

        assertNull(
            YtMusicRepository.resolveVideo(
                row,
                search = {
                    searches++
                    Result.failure(IllegalStateException("network down"))
                },
                pairing = pairing,
            ),
        )
        assertEquals(
            official,
            YtMusicRepository.resolveVideo(
                row,
                search = {
                    searches++
                    Result.success(listOf(SearchResult.Track(official)))
                },
                pairing = pairing,
            ),
        )
        assertEquals(queriesPerPass + 1, searches)
    }

    @Test
    fun `a signed-out pairing does not freeze a conclusive search miss`() = runBlocking {
        val row = song("SIGNEDOUT01", "After LIKE", "IVE")
        val queriesPerPass = TrackMatcher.queries(TrackMatcher.targetOf(row)).size
        var searches = 0
        val pairing = { YtMusicRepository.VersionPairing(null, answered = false) }
        val empty: suspend (String) -> Result<List<SearchResult>> = {
            searches++
            Result.success(emptyList())
        }

        assertNull(YtMusicRepository.resolveVideo(row, empty, pairing))
        assertNull(YtMusicRepository.resolveVideo(row, empty, pairing))
        assertEquals(2 * queriesPerPass, searches)
    }

    @Test
    fun `cancelling a waiter does not cancel the shared lookup`() = runBlocking {
        val row = song("CANCELJOIN01", "After LIKE", "IVE")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var searches = 0
        val search: suspend (String) -> Result<List<SearchResult>> = {
            searches++
            started.complete(Unit)
            release.await()
            Result.success(listOf(SearchResult.Track(official)))
        }
        val pairing = { YtMusicRepository.VersionPairing(null, answered = true) }

        val owner = launch { YtMusicRepository.resolveVideo(row, search, pairing) }
        started.await()
        var waiterResult: Song? = null
        val waiter = launch { waiterResult = YtMusicRepository.resolveVideo(row, search, pairing) }
        yield()

        owner.cancel()
        release.complete(Unit)
        waiter.join()

        assertNotNull(waiterResult)
        assertEquals(1, searches)
    }
}
