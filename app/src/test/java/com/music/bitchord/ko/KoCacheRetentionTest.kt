package com.music.bitchord.ko

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single purge rule: v1.9's keepsAddonCopy and keepAnalysis, plus the
 * playhead/timing keeps, now shared by the purge index and the drop pass.
 */
class KoCacheRetentionTest {

    private fun drop(
        origin: String? = "YOUTUBE",
        hasAnalysis: Boolean = false,
        hasPlayback: Boolean = false,
        hasPlayed: Boolean = false,
        keepAnalysis: Boolean = false,
        keepsAddonCopy: Boolean = false,
        keptByQueueOrTiming: Boolean = false,
    ) = cacheEntryShouldDrop(
        origin = origin,
        foreignOrigin = "OTHER",
        hasAnalysis = hasAnalysis,
        hasPlayback = hasPlayback,
        hasPlayed = hasPlayed,
        keepAnalysis = keepAnalysis,
        keepsAddonCopy = keepsAddonCopy,
        keptByQueueOrTiming = keptByQueueOrTiming,
    )

    @Test
    fun `a complete addon copy is kept`() {
        assertFalse(drop(origin = "OTHER", keepsAddonCopy = true))
    }

    @Test
    fun `an incomplete addon copy is dropped`() {
        assertTrue(drop(origin = "OTHER", keepsAddonCopy = false))
    }

    @Test
    fun `analysis-only copies are kept when the caller asked to keep analysis`() {
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, keepAnalysis = true))
    }

    @Test
    fun `analysis-only copies are dropped when analysis is not kept`() {
        assertTrue(drop(origin = "YOUTUBE", hasAnalysis = true, keepAnalysis = false))
    }

    @Test
    fun `an analysis copy playback has read is not analysis-only`() {
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, hasPlayback = true))
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, hasPlayed = true))
    }

    @Test
    fun `the playhead window or a timing keep spares every candidate`() {
        assertFalse(drop(origin = "OTHER", keepsAddonCopy = false, keptByQueueOrTiming = true))
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, keptByQueueOrTiming = true))
    }

    @Test
    fun `ordinary played entries are never candidates`() {
        assertFalse(drop(origin = "YOUTUBE"))
        assertFalse(drop(origin = "YOUTUBE", hasPlayback = true, hasPlayed = true))
        assertFalse(drop(origin = null))
    }
}
