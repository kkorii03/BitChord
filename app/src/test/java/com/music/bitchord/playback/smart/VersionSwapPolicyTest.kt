package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionSwapPolicyTest {

    @Test
    fun `a different song is taken off even though it is already playing`() {
        assertTrue(VersionSwapPolicy.refutesPlaying(0.2))
    }

    /** Too low to swap in, but the listener is hearing it: not enough to take it off. */
    @Test
    fun `a cover level match is neither proved nor refuted`() {
        assertFalse(VersionSwapPolicy.refutesPlaying(0.5))
        assertFalse(VersionSwapPolicy.shouldSwap(0.5))
    }

    @Test
    fun `the same recording stays`() {
        assertFalse(VersionSwapPolicy.refutesPlaying(0.8))
        assertTrue(VersionSwapPolicy.shouldSwap(0.8))
    }
}
