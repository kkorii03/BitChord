package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A start request that arrives while the last drain is finishing must not be
 * dropped. The old service asked only whether its drain field was null, so a
 * request in that window was accepted by Android and then ignored, leaving the
 * queued track with no worker.
 */
class DownloadDrainGateTest {

    @Test
    fun `a second start while draining does not launch a second drain`() {
        val gate = DrainGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
    }

    @Test
    fun `a finishing drain restarts when work is still queued`() {
        val gate = DrainGate()
        assertTrue(gate.begin())
        assertTrue(gate.finish(queueBusy = true))
        assertTrue(gate.begin())
    }

    @Test
    fun `a finishing drain stops when the queue is empty`() {
        val gate = DrainGate()
        assertTrue(gate.begin())
        assertFalse(gate.finish(queueBusy = false))
        assertTrue(gate.begin())
    }
}
