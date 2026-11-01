package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An output rebuild requested during a crossfade waits for the settled event
 * instead of polling the transition state.
 */
class OutputReconfigureGateTest {

    @Test
    fun `an idle request runs at once`() {
        val gate = OutputReconfigureGate()

        assertTrue(gate.request(transitioning = false))
        assertFalse("nothing was parked", gate.settled())
    }

    @Test
    fun `a request during a transition is parked`() {
        val gate = OutputReconfigureGate()

        assertFalse(gate.request(transitioning = true))
        assertTrue("the settled event runs the parked rebuild", gate.settled())
    }

    @Test
    fun `the settled event runs a parked rebuild only once`() {
        val gate = OutputReconfigureGate()

        assertFalse(gate.request(transitioning = true))
        assertTrue(gate.settled())
        assertFalse(gate.settled())
    }

    @Test
    fun `repeated requests in one transition collapse into one rebuild`() {
        val gate = OutputReconfigureGate()

        assertFalse(gate.request(transitioning = true))
        assertFalse(gate.request(transitioning = true))
        assertTrue(gate.settled())
        assertFalse(gate.settled())
    }

    @Test
    fun `an idle request after a parked one clears the parked rebuild`() {
        val gate = OutputReconfigureGate()

        assertFalse(gate.request(transitioning = true))
        assertTrue("the transition ended before the parked rebuild", gate.request(transitioning = false))
        assertFalse("the idle request already covered it", gate.settled())
    }
}
