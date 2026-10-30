package com.music.bitchord.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayQueuePolicyTest {

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
    fun `the threshold is the caller's to set`() {
        assertFalse(autoplayTopUpNeeded(true, false, 2, minAhead = 2))
        assertTrue(autoplayTopUpNeeded(true, false, 1, minAhead = 2))
    }

    @Test
    fun `a batch appended after the queue ended is started explicitly`() {
        assertTrue(
            shouldResumeAfterTopUp(
                playbackEnded = true,
                playWhenReady = true,
                appendedAtEnd = true,
            ),
        )
    }

    @Test
    fun `a paused queue is not un-paused by a late top-up`() {
        assertFalse(
            shouldResumeAfterTopUp(
                playbackEnded = true,
                playWhenReady = false,
                appendedAtEnd = true,
            ),
        )
    }

    @Test
    fun `a top-up that did not land at the end leaves the playhead alone`() {
        assertFalse(
            shouldResumeAfterTopUp(
                playbackEnded = true,
                playWhenReady = true,
                appendedAtEnd = false,
            ),
        )
        assertFalse(
            shouldResumeAfterTopUp(
                playbackEnded = false,
                playWhenReady = true,
                appendedAtEnd = true,
            ),
        )
    }
}
