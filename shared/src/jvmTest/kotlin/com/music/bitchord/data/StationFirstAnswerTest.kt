package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.model.TopPickMood
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A station used to wait for every seed radio before the first track was
 * queued, so the tap sat silent for the length of the slowest request. The
 * first answer now starts playback and the rest fill in behind it. These fakes
 * hold one radio open until the first has been handed over, which the old
 * "await them all" order could not survive.
 */
class StationFirstAnswerTest {

    private fun song(id: String, artist: String = "Artist") =
        Song(videoId = id, title = "Song $id", artist = artist, thumbnailUrl = null)

    private fun playlist(id: String) =
        ShelfItem(title = id, subtitle = "", thumbnailUrl = null, videoId = null, browseId = "VL$id")

    @Test
    fun `a fast radio starts the station while the other radios are still out`() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val firstEmitted = CompletableDeferred<List<Song>>()
        val history = listOf(song("seed1"), song("seed2"), song("seed3"))
        val fast = listOf(song("fast1"), song("fast2"))

        val building = async {
            TopPicks.buildFreshStation(
                kind = TopPickKind.PERSONAL_STATION,
                mix = {
                    slow.await()
                    emptyList()
                },
                history = { history },
                radio = { seed ->
                    if (seed.videoId == "seed1") {
                        fast
                    } else {
                        slow.await()
                        emptyList()
                    }
                },
                onFirstBatch = { firstEmitted.complete(it) },
            )
        }

        val early = withTimeout(2_000) { firstEmitted.await() }
        assertEquals(fast.map { it.videoId }.toSet(), early.map { it.videoId }.toSet())
        assertFalse(building.isCompleted, "the station must not wait for the slow radios to start")

        slow.complete(Unit)
        val station = withTimeout(2_000) { building.await() }.getOrNull().orEmpty()
        assertTrue(station.map { it.videoId }.containsAll(early.map { it.videoId }))
    }

    @Test
    fun `a filtered-out first answer does not start playback`() = runBlocking {
        val firstEmitted = CompletableDeferred<List<Song>>()
        val history = listOf(song("heard"), song("seed"))

        val result = withTimeout(2_000) {
            TopPicks.buildFreshStation(
                kind = TopPickKind.DISCOVERY_STATION,
                mix = { listOf(song("heard")) },
                history = { history },
                radio = { seed ->
                    if (seed.videoId == "seed") listOf(song("new")) else emptyList()
                },
                onFirstBatch = { firstEmitted.complete(it) },
            )
        }

        val early = firstEmitted.await()
        assertFalse(early.any { it.videoId == "heard" }, "an already-heard track must not start a discovery station")
        assertEquals(setOf("new"), early.map { it.videoId }.toSet())
        assertEquals(setOf("new"), result.getOrNull().orEmpty().map { it.videoId }.toSet())
    }

    @Test
    fun `the remaining radios fill the station after the first one`() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val firstEmitted = CompletableDeferred<List<Song>>()
        val history = listOf(song("seed1"), song("seed2"))
        val fast = listOf(song("fast1"))
        val later = listOf(song("later1"), song("later2"))

        val building = async {
            TopPicks.buildFreshStation(
                kind = TopPickKind.PERSONAL_STATION,
                mix = {
                    slow.await()
                    emptyList()
                },
                history = { history },
                radio = { seed ->
                    if (seed.videoId == "seed1") {
                        fast
                    } else {
                        slow.await()
                        later
                    }
                },
                onFirstBatch = { firstEmitted.complete(it) },
            )
        }

        val early = withTimeout(2_000) { firstEmitted.await() }
        slow.complete(Unit)
        val station = withTimeout(2_000) { building.await() }.getOrNull().orEmpty()

        assertEquals(fast.map { it.videoId }, station.take(early.size).map { it.videoId })
        assertTrue(station.map { it.videoId }.containsAll(fast.map { it.videoId } + later.map { it.videoId }))
    }

    @Test
    fun `a mood starts on its first playlist before the others answer`() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val firstEmitted = CompletableDeferred<List<Song>>()
        val shelves = listOf(
            HomeShelf(title = "Pop romance", items = listOf(playlist("first"), playlist("second"))),
        )

        val building = async {
            TopPicks.buildMoodQueue(
                mood = TopPickMood.LOVE,
                shelves = shelves,
                hints = emptyList(),
                onFirstBatch = { firstEmitted.complete(it) },
                songsOf = { item ->
                    if (item.browseId == "VLfirst") {
                        listOf(song("first1"), song("first2"))
                    } else {
                        slow.await()
                        listOf(song("second1"))
                    }
                },
            )
        }

        val early = withTimeout(2_000) { firstEmitted.await() }
        assertEquals(setOf("first1", "first2"), early.map { it.videoId }.toSet())
        assertFalse(building.isCompleted, "the mood must not wait for its other playlists to start")

        slow.complete(Unit)
        val station = withTimeout(2_000) { building.await() }.getOrNull().orEmpty()
        assertEquals(
            setOf("first1", "first2", "second1"),
            station.map { it.videoId }.toSet(),
        )
    }
}