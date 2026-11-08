package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Revert to original" reopens YouTube's upload at the same moment of the
 * song, not at the same number (sol-pairing Q3): a verified copy plays at
 * `offset + rate x youtube`, so its raw playhead is that much off YouTube's.
 */
class HqRevertPositionTest {

    @Test
    fun `a verified copy goes back through its own verdict`() {
        // The lossless file starts 2.4 s later than the upload: 62.4 s on it is 60 s on YouTube.
        assertEquals(60_000L, HqSwapPolicy.revertPositionMs(62_400L, 2_400L, 1.0, substitute = true))
        // A copy running 1 % fast: 101 s on it is 100 s on YouTube.
        assertEquals(100_000L, HqSwapPolicy.revertPositionMs(101_000L, 0L, 1.01, substitute = true))
        // Same arithmetic as the swap into the copy, inverted.
        val there = HqSwapPolicy.targetPositionMs(75_000L, 2_400L, 1.0)
        assertEquals(75_000L, HqSwapPolicy.revertPositionMs(there, 2_400L, 1.0, substitute = true))
    }

    @Test
    fun `YouTube already playing keeps its position and an unverified substitute is unknown`() {
        assertEquals(62_400L, HqSwapPolicy.revertPositionMs(62_400L, null, null, substitute = false))
        assertNull(HqSwapPolicy.revertPositionMs(62_400L, null, null, substitute = true))
    }
}
