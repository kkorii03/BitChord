package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue snapshot is read off the main thread, so a track the listener
 * picked while that read was in flight must win over the restored queue.
 */
class QueueRestoreGuardTest {

    @Test
    fun `an empty player may take the restored queue`() {
        assertTrue(queueRestoreApplies(existingMediaItemCount = 0))
    }

    @Test
    fun `a queue the listener already chose is never replaced`() {
        assertFalse(queueRestoreApplies(existingMediaItemCount = 1))
        assertFalse(queueRestoreApplies(existingMediaItemCount = 25))
    }
}
