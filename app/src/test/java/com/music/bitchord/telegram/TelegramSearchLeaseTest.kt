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

    /** [KO] OPT1: search -> metadata in one walk used to close TDLib and start a new session between. */
    @Test
    fun aWalkKeepsTheClientOpenBetweenItsStepsAndReleasesItAtTheEnd() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        gate.beginWalk()
        assertTrue(gate.beginSearchLease())
        assertFalse(gate.endSearchLease())
        assertTrue(gate.shouldBeOpen)
        // The metadata step finds the client still open.
        assertFalse(gate.beginStreamLease())
        assertFalse(gate.endStreamLease())
        assertTrue(gate.endWalk())
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun aWalkNeverOpensTheClientByItself() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        gate.beginWalk()
        assertFalse(gate.shouldBeOpen)
        assertFalse(gate.endWalk())
        assertFalse(gate.shouldBeOpen)
        // A later search alone still opens and closes as before.
        assertTrue(gate.beginSearchLease())
        assertTrue(gate.endSearchLease())
    }

    @Test
    fun overlappingWalksShareOneLeaseUntilTheLastEnds() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        gate.beginWalk()
        gate.beginWalk()
        assertTrue(gate.beginSearchLease())
        assertFalse(gate.endSearchLease())
        assertFalse(gate.endWalk())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endWalk())
        assertFalse(gate.shouldBeOpen)
    }
}
