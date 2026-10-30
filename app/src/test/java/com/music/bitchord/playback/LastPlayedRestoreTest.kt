package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LastPlayedRestoreTest {

    private lateinit var preferences: FakePreferences

    @Before
    fun setUp() {
        preferences = FakePreferences()
        LastPlayed.initForTesting(preferences.prefs)
    }

    @Test
    fun `a queue change does not restore the previous track's playhead`() {
        LastPlayed.saveQueueImmediately(listOf(song("old")), index = 0, positionMs = 123_456L)
        assertEquals(123_456L, LastPlayed.load()!!.positionMs)

        LastPlayed.saveQueue(listOf(song("new-a"), song("new-b")), index = 1, positionMs = 0L)

        val restored = LastPlayed.load()!!
        assertEquals(0L, restored.positionMs)
        assertEquals(1, restored.index)
        assertEquals("new-b", restored.songs[1].videoId)
    }

    @Test
    fun `a queue change keeps the playhead it was handed`() {
        LastPlayed.saveQueue(listOf(song("a"), song("b")), index = 0, positionMs = 4_200L)

        val restored = LastPlayed.load()!!
        assertEquals(4_200L, restored.positionMs)
        assertEquals("a", restored.songs[restored.index].videoId)
    }

    @Test
    fun `a playhead captured before a queue change cannot overwrite the new queue`() {
        LastPlayed.saveQueueImmediately(listOf(song("old")), index = 0, positionMs = 123_456L)
        // The sampler queued this write for the old queue, but the queue edit's
        // snapshot is written before the write gets to run.
        val staleGeneration = LastPlayed.currentQueueGeneration()

        LastPlayed.saveQueue(listOf(song("new-a"), song("new-b")), index = 1, positionMs = 0L)
        LastPlayed.savePlaybackState(
            index = 0,
            positionMs = 123_456L,
            generation = staleGeneration,
        )

        val restored = LastPlayed.load()!!
        assertEquals(0L, restored.positionMs)
        assertEquals(1, restored.index)
        assertEquals("new-b", restored.songs[1].videoId)
    }

    @Test
    fun `a playhead captured after a queue change still advances`() {
        LastPlayed.saveQueue(listOf(song("a"), song("b")), index = 0, positionMs = 4_200L)
        val generation = LastPlayed.currentQueueGeneration()

        LastPlayed.savePlaybackState(index = 1, positionMs = 5_000L, generation = generation)

        val restored = LastPlayed.load()!!
        assertEquals(1, restored.index)
        assertEquals(5_000L, restored.positionMs)
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )
}
