package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one guard that keeps a deferred party resume from starting the listener's
 * own queue after they have left the party.
 */
class PartyDeferredPlayTest {

    @Test
    fun `a pending resume is allowed while the party is still live`() {
        assertTrue(deferredPlayAllowed(inParty = true, pending = true))
    }

    @Test
    fun `leaving the party revokes the pending resume`() {
        assertFalse(deferredPlayAllowed(inParty = false, pending = true))
    }

    @Test
    fun `nothing is started when no resume was pending`() {
        assertFalse(deferredPlayAllowed(inParty = true, pending = false))
    }
}
