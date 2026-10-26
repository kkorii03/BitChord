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
    fun `a one track queue repeating with repeat off in settings needs a refill`() {
        // The reported ALsvdSA9tOU lap: idx=0/1, reason=REPEAT, while the
        // listener's repeat mode was off.
        assertTrue(
            staleRepeatNeedsAutoplay(
                transitionReasonRepeat = true,
                currentIndex = 0,
                itemCount = 1,
                playerRepeatMode = REPEAT_ALL,
                settingsRepeatMode = REPEAT_OFF,
            ),
        )
        assertTrue(
            staleRepeatNeedsAutoplay(
                transitionReasonRepeat = true,
                currentIndex = 0,
                itemCount = 1,
                playerRepeatMode = REPEAT_ONE,
                settingsRepeatMode = REPEAT_OFF,
            ),
        )
    }

    @Test
    fun `resyncing the stale repeat lets the one track queue refill`() {
        val settings = REPEAT_OFF
        val player = REPEAT_ALL
        val stale = staleRepeatNeedsAutoplay(
            transitionReasonRepeat = true,
            currentIndex = 0,
            itemCount = 1,
            playerRepeatMode = player,
            settingsRepeatMode = settings,
        )
        val effective = if (stale) settings else player
        // Without the resync this read repeat-all and returned false, which is
        // the state the phone sat in for a whole 4-minute lap at a time.
        assertTrue(
            autoplayTopUpNeeded(
                enabled = true,
                repeatAll = effective == REPEAT_ALL,
                queuedAhead = 0,
            ),
        )
    }

    @Test
    fun `an intentional repeat or an ordinary transition is left alone`() {
        assertFalse(
            staleRepeatNeedsAutoplay(
                transitionReasonRepeat = true,
                currentIndex = 0,
                itemCount = 1,
                playerRepeatMode = REPEAT_ALL,
                settingsRepeatMode = REPEAT_ALL,
            ),
        )
        assertFalse(
            staleRepeatNeedsAutoplay(
                transitionReasonRepeat = false,
                currentIndex = 0,
                itemCount = 1,
                playerRepeatMode = REPEAT_ALL,
                settingsRepeatMode = REPEAT_OFF,
            ),
        )
        assertFalse(
            staleRepeatNeedsAutoplay(
                transitionReasonRepeat = true,
                currentIndex = 1,
                itemCount = 3,
                playerRepeatMode = REPEAT_ALL,
                settingsRepeatMode = REPEAT_OFF,
            ),
        )
    }

    private companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }
}
