package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mix pass left running by the bundle's step deadline is still the bundle's
 * work: replacing its row must stop it (11-02: the analysis held the single
 * decode slot long after the next-prep bundle was cancelled). A pass whose row
 * became the playing one is the crossfade's own and must be kept.
 */
class PreparedMixPassTest {

    private val cancelled = mutableListOf<String>()
    private val pass = PreparedMixPass { cancelled += it }

    @Test
    fun `a replaced row stops the pass it requested`() {
        pass.requested("next")
        pass.cancelUnlessPlaying(playingVideoId = "current")
        assertEquals(listOf("next"), cancelled)
    }

    @Test
    fun `the row becoming the playing one keeps its pass`() {
        pass.requested("next")
        pass.cancelUnlessPlaying(playingVideoId = "next")
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `a finished pass is not cancelled later`() {
        pass.requested("next")
        pass.finished()
        pass.cancelUnlessPlaying(playingVideoId = "current")
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `a bundle that never requested a pass cancels nothing`() {
        pass.cancelUnlessPlaying(playingVideoId = "current")
        assertTrue(cancelled.isEmpty())
    }
}
