package com.music.bitchord.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata resolution is a playback lease: a queue transition while the screen
 * is off must be able to open TDLib, and overlapping lookups or streams must
 * not close it until the last one is done.
 */
class TelegramStreamLeaseTest {

    @Test
    fun streamLeaseOpensTheClientInTheBackground() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginStreamLease())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endStreamLease())
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun overlappingLeasesKeepTheClientOpenUntilTheLastRelease() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginStreamLease())
        assertFalse(gate.beginStreamLease())
        assertTrue(gate.shouldBeOpen)
        assertFalse(gate.endStreamLease())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endStreamLease())
        assertFalse(gate.shouldBeOpen)
    }
}
