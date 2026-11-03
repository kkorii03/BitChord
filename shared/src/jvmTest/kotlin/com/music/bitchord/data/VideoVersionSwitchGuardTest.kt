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
import kotlin.test.assertNull

/** The video switch keeps its 1.8 guards: no blank search, no take, no repeated miss. */
class VideoVersionSwitchGuardTest {

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

    private val official = song("OFFICIAL001", "After LIKE", "IVE", "3:12", "UC_IVE")
    private val stage = song("STAGE000001", "After LIKE stage", "IVE", "3:12", "UC_IVE")
    private val pairing = { YtMusicRepository.VersionPairing(null, answered = true) }

    @Test
    fun `a stage upload is not the video version`() = runBlocking {
        val row = song("AUDIO000001", "After LIKE", "IVE", "3:12")
        val search: suspend (String) -> Result<List<SearchResult>> = {
            Result.success(listOf(SearchResult.Track(stage)))
        }

        assertNull(YtMusicRepository.resolveVideo(row, search, pairing))
    }

    @Test
    fun `a conclusive miss is remembered`() = runBlocking {
        val row = song("AUDIO000002", "After LIKE", "IVE", "3:12")
        val queriesPerPass = TrackMatcher.queries(TrackMatcher.targetOf(row)).size
        var searches = 0
        val empty: suspend (String) -> Result<List<SearchResult>> = {
            searches++
            Result.success(emptyList())
        }

        assertNull(YtMusicRepository.resolveVideo(row, empty, pairing))
        assertNull(YtMusicRepository.resolveVideo(row, empty, pairing))
        assertEquals(queriesPerPass, searches)
    }

    @Test
    fun `a failed search does not freeze the miss`() = runBlocking {
        val row = song("AUDIO000003", "After LIKE", "IVE", "3:12")
        var searches = 0

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
    }

    @Test
    fun `a row without a header is not searched`() = runBlocking {
        val row = song("BLANK000001", "", "IVE")
        var searches = 0
        val search: suspend (String) -> Result<List<SearchResult>> = {
            searches++
            error("must not search a blank row")
        }

        assertNull(YtMusicRepository.resolveVideo(row, search, pairing))
        assertEquals(0, searches)
    }

    @Test
    fun `concurrent asks share one search`() = runBlocking {
        val row = song("AUDIO000004", "After LIKE", "IVE", "3:12")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var searches = 0
        val search: suspend (String) -> Result<List<SearchResult>> = {
            searches++
            started.complete(Unit)
            release.await()
            Result.success(listOf(SearchResult.Track(official)))
        }

        val first = launch { YtMusicRepository.resolveVideo(row, search, pairing) }
        started.await()
        val second = launch { YtMusicRepository.resolveVideo(row, search, pairing) }
        yield()
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, searches)
    }
}
