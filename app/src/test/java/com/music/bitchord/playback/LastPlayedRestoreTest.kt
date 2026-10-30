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

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )
}
