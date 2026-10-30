package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayQueuePolicyTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = "Song $id",
        artist = "Artist $id",
        thumbnailUrl = null,
    )

    @Test
    fun `a queue with fewer than five tracks left needs a top-up`() {
        assertTrue(autoplayTopUpNeeded(enabled = true, repeatAll = false, queuedAhead = 0))
        assertTrue(autoplayTopUpNeeded(true, false, 4))
    }

    @Test
    fun `five or more tracks ahead are enough to skip the radio request`() {
        assertFalse(autoplayTopUpNeeded(true, false, 5))
        assertFalse(autoplayTopUpNeeded(true, false, 9))
    }

    @Test
    fun `disabled autoplay and repeat all never fetch`() {
        assertFalse(autoplayTopUpNeeded(false, false, 0))
        assertFalse(autoplayTopUpNeeded(true, true, 0))
    }

    @Test
    fun `repeat one never fetches because the same track plays again`() {
        // The queue does not advance while repeat-one loops the current track,
        // so a station fetched now cannot be reached. It is fetched again when
        // the listener leaves repeat-one.
        assertFalse(autoplayTopUpNeeded(true, false, 0, repeatOne = true))
        assertFalse(autoplayTopUpNeeded(true, false, 4, repeatOne = true))
    }

    @Test
    fun `the threshold is the caller's to set`() {
        assertFalse(autoplayTopUpNeeded(true, false, 2, minAhead = 2))
        assertTrue(autoplayTopUpNeeded(true, false, 1, minAhead = 2))
    }

    @Test
    fun `a top-up drops waiting rows and keeps only the room left`() {
        val selected = autoplayTopUpSelection(
            suggestions = listOf(song("new-1"), song("waiting"), song("new-2"), song("new-3")),
            waitingIds = setOf("waiting"),
            waitingAutoplay = 8,
            maxAutoplay = 10,
        )
        assertEquals(listOf("new-1", "new-2"), selected.map { it.videoId })
    }

    @Test
    fun `a full tail takes no suggestions at all`() {
        val selected = autoplayTopUpSelection(
            suggestions = listOf(song("new")),
            waitingIds = emptySet(),
            waitingAutoplay = 10,
            maxAutoplay = 10,
        )
        assertTrue(selected.isEmpty())
    }
}
