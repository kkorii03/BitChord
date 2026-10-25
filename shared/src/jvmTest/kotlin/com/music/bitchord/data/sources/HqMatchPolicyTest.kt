package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HqMatchPolicyTest {

    @Test
    fun `a close match with a small start offset is accepted`() {
        val verdict = HqMatchPolicy.verdict(matchPercent = 85, offsetMs = 1_200L, rate = 1.001)
        assertTrue(verdict.accepted)
        assertEquals(1_200L, verdict.offsetMs)
    }

    @Test
    fun `a weak match is rejected`() {
        val verdict = HqMatchPolicy.verdict(matchPercent = 74, offsetMs = 0L, rate = 1.0)
        assertFalse(verdict.accepted)
    }

    @Test
    fun `the match floor is inclusive`() {
        assertTrue(HqMatchPolicy.verdict(HqMatchPolicy.MIN_MATCH_PERCENT, 0L, 1.0).accepted)
        assertFalse(HqMatchPolicy.verdict(HqMatchPolicy.MIN_MATCH_PERCENT - 1, 0L, 1.0).accepted)
    }

    @Test
    fun `a different pace is rejected`() {
        assertFalse(HqMatchPolicy.verdict(90, 0L, 1.2).accepted)
        assertFalse(HqMatchPolicy.verdict(90, 0L, 0.8).accepted)
        assertTrue(HqMatchPolicy.verdict(90, 0L, HqMatchPolicy.MIN_RATE).accepted)
        assertTrue(HqMatchPolicy.verdict(90, 0L, HqMatchPolicy.MAX_RATE).accepted)
    }

    @Test
    fun `a start offset longer than the compared window is rejected`() {
        assertFalse(HqMatchPolicy.verdict(90, HqMatchPolicy.MAX_ABS_OFFSET_MS + 1, 1.0).accepted)
        assertFalse(HqMatchPolicy.verdict(90, -HqMatchPolicy.MAX_ABS_OFFSET_MS - 1, 1.0).accepted)
        assertTrue(HqMatchPolicy.verdict(90, HqMatchPolicy.MAX_ABS_OFFSET_MS, 1.0).accepted)
    }

    @Test
    fun `a missing match is zero percent`() {
        assertFalse(HqMatchPolicy.verdict(null, 0L, 1.0).accepted)
    }

    @Test
    fun `offset application is the same arithmetic as the lyric map`() {
        assertEquals(11_210L, HqMatchPolicy.applyOffset(10_000L, 1_200L, 1.001))
        assertEquals(0L, HqMatchPolicy.applyOffset(0L, 0L, 1.0))
        assertEquals(5_000L, HqMatchPolicy.applyOffset(5_000L, 0L, 1.0))
    }

    @Test
    fun `the lyric map moves YouTube time onto the candidate clock`() {
        val map = requireNotNull(HqMatchPolicy.mapFor(offsetMs = 1_200L, rate = 1.001))
        assertEquals(1_200L, map.map(0L))
        assertEquals(11_210L, map.map(10_000L))
    }
}
