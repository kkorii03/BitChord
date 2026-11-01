package com.music.bitchord.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telegram playback and the counted stream/search leases are independent
 * reasons to keep TDLib open. A search finishing must not end playback, and a
 * playback stop event must not close the client under a stream that is still
 * reading.
 */
class TelegramPlaybackLeaseTest {

    @Test
    fun endingALeaseDoesNotEndTelegramPlayback() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.onTelegramPlaybackActive(true))
        assertTrue(gate.beginSearchLease())
        assertTrue(gate.endSearchLease())

        assertTrue(gate.telegramPlaybackActive)
        assertTrue(gate.shouldBeOpen)
    }

    @Test
    fun endingPlaybackDoesNotCloseWhileALeaseIsOutstanding() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginStreamLease())
        assertTrue(gate.onTelegramPlaybackActive(false))

        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endStreamLease())
        assertFalse(gate.shouldBeOpen)
    }
}
