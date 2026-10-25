package com.music.bitchord.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The decoder ledger the battery line is built from: every open decoder is
 * counted, a repeated name from two players is two decoders, and closing one
 * leaves the other.
 */
class DecoderMonitorTest {

    @Before
    fun setUp() {
        DecoderMonitor.clear()
        PowerState.update(true)
    }

    @After
    fun tearDown() {
        DecoderMonitor.clear()
        PowerState.update(true)
    }

    @Test
    fun `two players on the same codec are two decoders with distinct names`() {
        DecoderMonitor.start("player-a:c2.android.opus.decoder")
        DecoderMonitor.start("player-b:c2.android.opus.decoder")

        assertEquals(2, DecoderMonitor.activeCount())
        assertEquals(
            listOf("player-a:c2.android.opus.decoder", "player-b:c2.android.opus.decoder"),
            DecoderMonitor.activeNames(),
        )
    }

    @Test
    fun `closing one player's decoder leaves the other running`() {
        DecoderMonitor.start("player-a:c2.android.opus.decoder")
        DecoderMonitor.start("player-b:c2.android.opus.decoder")

        DecoderMonitor.stop("player-b:c2.android.opus.decoder")

        assertEquals(1, DecoderMonitor.activeCount())
        assertEquals(listOf("player-a:c2.android.opus.decoder"), DecoderMonitor.activeNames())
    }

    @Test
    fun `repeated start and stop of one name stays balanced`() {
        DecoderMonitor.start("automix")
        DecoderMonitor.start("automix")
        DecoderMonitor.stop("automix")

        assertEquals(1, DecoderMonitor.activeCount())

        DecoderMonitor.stop("automix")
        assertTrue(DecoderMonitor.activeNames().isEmpty())
    }

    @Test
    fun `power state tracks the screen without polling`() {
        assertTrue(PowerState.screenOn)

        PowerState.update(false)
        assertFalse(PowerState.screenOn)

        PowerState.update(false)
        assertFalse(PowerState.screenOn)

        PowerState.update(true)
        assertTrue(PowerState.screenOn)
    }
}
