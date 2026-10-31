package com.music.bitchord.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client must not be opened by app start, must close when the app is in
 * the background with no playback, and must stay open while playing.
 */
class TelegramClientGateTest {

    @Test
    fun appStartDoesNotHoldTheClientOpen() {
        val gate = TelegramClientGate()
        assertFalse(gate.shouldBeOpen)
        assertTrue(gate.openReasons.isEmpty())
    }

    @Test
    fun foregroundScreenOpensAndLeavingCloses() {
        val gate = TelegramClientGate()
        gate.onForeground(true)
        assertTrue(gate.acquire("settings"))
        assertTrue(gate.shouldBeOpen)
        assertFalse(gate.release("settings"))
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun backgroundWithoutPlaybackCloses() {
        val gate = TelegramClientGate()
        gate.onForeground(true)
        gate.acquire("settings")
        assertFalse(gate.onForeground(false))
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun playbackKeepsTheClientOpenInTheBackground() {
        val gate = TelegramClientGate()
        gate.onForeground(true)
        gate.acquire("settings")
        gate.onForeground(false)
        assertFalse(gate.shouldBeOpen)
        assertTrue(gate.onPlaybackActive(true))
        assertTrue(gate.shouldBeOpen)
        assertFalse(gate.onPlaybackActive(false))
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun playbackAloneOpensAndKeepsTheClientOpen() {
        val gate = TelegramClientGate()
        // Playback is its own reason: no screen holder is required, and the
        // background does not close a playing client.
        assertTrue(gate.onPlaybackActive(true))
        assertTrue(gate.shouldBeOpen)
        gate.onForeground(false)
        assertTrue(gate.shouldBeOpen)
        assertFalse(gate.onPlaybackActive(false))
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun foregroundHolderDoesNotKeepTheClientOpenWithoutForeground() {
        val gate = TelegramClientGate()
        gate.acquire("settings")
        assertFalse(gate.shouldBeOpen)
        assertTrue(gate.onForeground(true))
        assertTrue(gate.shouldBeOpen)
    }

    @Test
    fun returningToForegroundReopensAHeldClient() {
        val gate = TelegramClientGate()
        gate.onForeground(true)
        gate.acquire("settings")
        gate.onForeground(false)
        assertFalse(gate.shouldBeOpen)
        assertTrue(gate.onForeground(true))
        assertTrue(gate.shouldBeOpen)
    }
}
