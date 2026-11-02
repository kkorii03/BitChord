package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.canonicalIndex
import com.music.bitchord.playback.queueEntryId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home station used to hand its whole first answer to the player at once,
 * so a long first playlist was mapped before any sound started, and its tail
 * was appended without queue metadata. These checks pin the incremental
 * behaviour the player now relies on.
 */
class StationPlaybackTest {

    private fun song(id: String) = Song(videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = null)

    @Test
    fun `starts on the first track before the rest of the first answer is known`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val firstPlayed = CompletableDeferred<Unit>()
        val first = listOf(song("a1"), song("a2"), song("a3"))
        val later = listOf(song("b1"))
        val played = mutableListOf<List<Song>>()
        val appended = mutableListOf<List<Song>>()

        val running = async {
            playStationIncrementally(
                load = { onFirst ->
                    onFirst(first)
                    gate.await()
                    Result.success(first + later)
                },
                isCurrent = { true },
                begin = {},
                playFirst = {
                    played += it
                    firstPlayed.complete(Unit)
                },
                append = { appended += it },
                onEmpty = { error("must not report an empty station") },
            )
        }

        withTimeout(2_000) { firstPlayed.await() }
        assertEquals(listOf("a1"), played.single().map { it.videoId })
        assertFalse("the rest of the answer must not delay the first track", running.isCompleted)

        gate.complete(Unit)
        assertTrue(running.await())
        assertEquals(listOf("a2", "a3"), appended[0].map { it.videoId })
        assertEquals(listOf("b1"), appended[1].map { it.videoId })
        assertEquals(1, played.size)
    }

    @Test
    fun `does not append a tail after a newer request replaced the station`() = runBlocking {
        val first = listOf(song("a1"))
        val later = listOf(song("b1"))
        val appended = mutableListOf<List<Song>>()
        var current = true

        val started = playStationIncrementally(
            load = { onFirst ->
                onFirst(first)
                current = false
                Result.success(first + later)
            },
            isCurrent = { current },
            begin = {},
            playFirst = {},
            append = { appended += it },
            onEmpty = { error("must not report an empty station") },
        )

        assertTrue(started)
        assertTrue("the replaced queue must not receive the old station's tail", appended.isEmpty())
    }

    @Test
    fun `starts the whole queue once when no early answer arrived`() = runBlocking {
        val songs = listOf(song("a1"), song("a2"))
        val played = mutableListOf<List<Song>>()

        playStationIncrementally(
            load = { Result.success(songs) },
            isCurrent = { true },
            begin = {},
            playFirst = { played += it },
            append = { error("there is no earlier answer to append to") },
            onEmpty = { error("must not report an empty station") },
        )

        assertEquals(songs.map { it.videoId }, played.single().map { it.videoId })
    }

    @Test
    fun `an appended tail is stamped as queue entries and continues the canonical context`() {
        QueueShuffle.clearCanonicalContext()
        val songs = listOf(song("a"), song("b"))

        val (items, context) = buildAppendedItems(songs, emptyList())

        assertEquals(listOf("a", "b"), items.map { it.mediaId })
        assertNotNull(items[0].queueEntryId)
        assertEquals(0, items[0].canonicalIndex)
        assertEquals(1, items[1].canonicalIndex)
        assertEquals(2, context.size)
    }
}
