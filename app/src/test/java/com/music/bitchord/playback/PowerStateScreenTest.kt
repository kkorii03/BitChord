package com.music.bitchord.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyric re-measure loop waits for the screen-on event instead of waking
 * every 8 s while the screen is off and the measurement can only return null.
 */
class PowerStateScreenTest {

    @After
    fun reset() {
        PowerState.update(true)
    }

    @Test
    fun `work waiting for the screen resumes on the screen-on event`() = runTest {
        PowerState.update(false)
        assertFalse(PowerState.screenOn)

        val waiting = async { PowerState.screen.first { it } }
        runCurrent()
        assertFalse(waiting.isCompleted)

        PowerState.update(true)
        runCurrent()
        assertTrue(waiting.await())
    }
}
