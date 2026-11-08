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
        keptForAnalysis: Boolean = false,
    ) = cacheEntryShouldDrop(
        origin = origin,
        foreignOrigin = "OTHER",
        hasAnalysis = hasAnalysis,
        hasPlayback = hasPlayback,
        hasPlayed = hasPlayed,
        keepAnalysis = keepAnalysis,
        keepsAddonCopy = keepsAddonCopy,
        keptByQueueOrTiming = keptByQueueOrTiming,
        keptForAnalysis = keptForAnalysis,
    )

    // 11-08 phone log: every repeat analysis round (79) came after a drop pass
    // had removed the copy the analysis had just fetched, and downloaded it again.
    @Test
    fun `an analysis copy asked for recently survives the drop pass`() {
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, keptForAnalysis = true))
    }

    @Test
    fun `the analysis keep never spares a partial addon copy`() {
        assertTrue(drop(origin = "OTHER", keepsAddonCopy = false, keptForAnalysis = true))
        assertTrue(drop(origin = "OTHER", hasAnalysis = true, keepsAddonCopy = false, keptForAnalysis = true))
    }

    @Test
    fun `the analysis keep lasts ten minutes from the last ask`() {
        val retention = KoCacheRetention()
        assertFalse(retention.keptForAnalysis("a", nowMs = 0L))
        retention.keepForAnalysis("a", nowMs = 1_000L)
        assertTrue(retention.keptForAnalysis("a", nowMs = 1_000L + 9 * 60_000L))
        assertFalse(retention.keptForAnalysis("a", nowMs = 1_000L + 10 * 60_000L))
        retention.keepForAnalysis("a", nowMs = 1_000L + 10 * 60_000L)
        assertTrue(retention.keptForAnalysis("a", nowMs = 1_000L + 15 * 60_000L))
    }

    @Test
    fun `the analysis keep holds only the most recent twelve tracks`() {
        val retention = KoCacheRetention()
        (0 until 13).forEach { retention.keepForAnalysis("t$it", nowMs = it.toLong()) }
        assertFalse(retention.keptForAnalysis("t0", nowMs = 20L))
        assertTrue(retention.keptForAnalysis("t1", nowMs = 20L))
        assertTrue(retention.keptForAnalysis("t12", nowMs = 20L))
    }

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
