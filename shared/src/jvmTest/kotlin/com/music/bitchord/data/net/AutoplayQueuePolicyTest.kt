package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `repeat one still fills the queue while the current track loops`() {
        // AutoPlay on means the queue is filled. Repeat-one only loops the
        // current track; the prepared tail is heard the moment it is left.
        assertTrue(autoplayTopUpNeeded(enabled = true, repeatAll = false, queuedAhead = 0))
        assertTrue(autoplayTopUpNeeded(true, false, 4))
    }

    @Test
    fun `the threshold is the caller's to set`() {
        assertFalse(autoplayTopUpNeeded(true, false, 2, minAhead = 2))
        assertTrue(autoplayTopUpNeeded(true, false, 1, minAhead = 2))
    }

    @Test
    fun `the autoplay switch is drawn on only when repeat all is not holding it`() {
        assertTrue(autoplayShownEnabled(true, AUTOPLAY_REPEAT_MODE_OFF))
        assertTrue(autoplayShownEnabled(true, AUTOPLAY_REPEAT_MODE_ONE))
        assertFalse(autoplayShownEnabled(true, AUTOPLAY_REPEAT_MODE_ALL))
        assertFalse(autoplayShownEnabled(false, AUTOPLAY_REPEAT_MODE_OFF))
        assertFalse(autoplayShownEnabled(false, AUTOPLAY_REPEAT_MODE_ONE))
        assertFalse(autoplayShownEnabled(false, AUTOPLAY_REPEAT_MODE_ALL))
    }

    @Test
    fun `turning autoplay on releases repeat all and fills immediately`() {
        val result = autoplayToggleResult(
            autoplay = false,
            repeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            heldAutoplay = null,
        )
        assertTrue(result.autoplay)
        assertEquals(AUTOPLAY_REPEAT_MODE_OFF, result.repeatMode)
        assertNull(result.heldAutoplay)
        assertTrue(
            autoplayTopUpNeeded(
                enabled = result.autoplay,
                repeatAll = result.repeatMode == AUTOPLAY_REPEAT_MODE_ALL,
                queuedAhead = 0,
            ),
        )
    }

    @Test
    fun `turning autoplay off while repeat all is on drops the held value`() {
        val result = autoplayToggleResult(
            autoplay = true,
            repeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            heldAutoplay = true,
        )
        assertFalse(result.autoplay)
        assertEquals(AUTOPLAY_REPEAT_MODE_ALL, result.repeatMode)
        assertNull(result.heldAutoplay)
    }

    @Test
    fun `repeat all switches autoplay off and remembers it was on`() {
        val result = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_OFF,
            repeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            autoplay = true,
            heldAutoplay = null,
        )
        assertFalse(result.autoplay)
        assertEquals(true, result.heldAutoplay)
    }

    @Test
    fun `leaving repeat all restores the autoplay it switched off`() {
        val toRepeatOne = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            repeatMode = AUTOPLAY_REPEAT_MODE_ONE,
            autoplay = false,
            heldAutoplay = true,
        )
        assertTrue(toRepeatOne.autoplay)
        assertNull(toRepeatOne.heldAutoplay)

        val toOff = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            repeatMode = AUTOPLAY_REPEAT_MODE_OFF,
            autoplay = false,
            heldAutoplay = true,
        )
        assertTrue(toOff.autoplay)
        assertNull(toOff.heldAutoplay)
    }

    @Test
    fun `leaving repeat all keeps autoplay off when it was already off`() {
        val entering = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_OFF,
            repeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            autoplay = false,
            heldAutoplay = null,
        )
        assertFalse(entering.autoplay)
        assertEquals(false, entering.heldAutoplay)

        val leaving = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_ALL,
            repeatMode = AUTOPLAY_REPEAT_MODE_OFF,
            autoplay = false,
            heldAutoplay = entering.heldAutoplay,
        )
        assertFalse(leaving.autoplay)
        assertNull(leaving.heldAutoplay)
    }

    @Test
    fun `repeat one and off changes leave autoplay alone`() {
        val toRepeatOne = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_OFF,
            repeatMode = AUTOPLAY_REPEAT_MODE_ONE,
            autoplay = true,
            heldAutoplay = null,
        )
        assertTrue(toRepeatOne.autoplay)
        assertNull(toRepeatOne.heldAutoplay)

        val toOff = repeatModeAutoplayResult(
            previousRepeatMode = AUTOPLAY_REPEAT_MODE_ONE,
            repeatMode = AUTOPLAY_REPEAT_MODE_OFF,
            autoplay = true,
            heldAutoplay = null,
        )
        assertTrue(toOff.autoplay)
        assertNull(toOff.heldAutoplay)
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

    @Test
    fun `the queue subtitle says AutoPlay is off even with rows already queued`() {
        assertEquals(
            AutoplayQueueDescription.DISABLED,
            autoplayQueueDescription(
                hasTracks = true,
                autoplayEnabled = false,
                currentSeedId = "current",
                emptyRequestSeedId = null,
            ),
        )
    }

    @Test
    fun `the queue subtitle reports a miss only for a real zero-song request`() {
        assertEquals(
            AutoplayQueueDescription.QUEUED,
            autoplayQueueDescription(
                hasTracks = true,
                autoplayEnabled = true,
                currentSeedId = "current",
                emptyRequestSeedId = "current",
            ),
        )
        assertEquals(
            AutoplayQueueDescription.EMPTY,
            autoplayQueueDescription(
                hasTracks = false,
                autoplayEnabled = true,
                currentSeedId = "current",
                emptyRequestSeedId = "current",
            ),
        )
        assertEquals(
            AutoplayQueueDescription.WAITING,
            autoplayQueueDescription(
                hasTracks = false,
                autoplayEnabled = true,
                currentSeedId = "current",
                emptyRequestSeedId = null,
            ),
        )
        // A miss recorded for the previous track must not describe this one.
        assertEquals(
            AutoplayQueueDescription.WAITING,
            autoplayQueueDescription(
                hasTracks = false,
                autoplayEnabled = true,
                currentSeedId = "next",
                emptyRequestSeedId = "previous",
            ),
        )
    }

    @Test
    fun `the queue subtitle says autoplay is off regardless of an old miss`() {
        assertEquals(
            AutoplayQueueDescription.DISABLED,
            autoplayQueueDescription(
                hasTracks = false,
                autoplayEnabled = false,
                currentSeedId = "current",
                emptyRequestSeedId = "current",
            ),
        )
    }
}
