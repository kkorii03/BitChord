package com.music.bitchord.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A search is a counted lease, not a foreground holder: a queue transition
 * while the screen is off must keep TDLib open, overlapping searches must not
 * close it under one another, and only the last release may close it.
 */
class TelegramSearchLeaseTest {

    @Test
    fun searchLeaseKeepsTheClientOpenAfterBackgrounding() {
        val gate = TelegramClientGate()
        gate.onForeground(true)
        assertTrue(gate.beginSearchLease())
        assertTrue(gate.shouldBeOpen)
        gate.onForeground(false)
        // The old search holder only counted in the foreground; the lease must
        // survive the transition.
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endSearchLease())
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun concurrentSearchesKeepTheClientOpenUntilTheLastRelease() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginSearchLease())
        assertFalse(gate.beginSearchLease())
        assertFalse(gate.endSearchLease())
        // The first search finishing must not close the client under the second.
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endSearchLease())
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun searchAndStreamShareOneCount() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginSearchLease())
        assertFalse(gate.beginStreamLease())
        assertFalse(gate.endSearchLease())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endStreamLease())
        assertFalse(gate.shouldBeOpen)
    }
}
