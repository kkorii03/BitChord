package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStatsSeedTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test
    fun `the plan has thirty-six plays across twelve songs and six artists`() {
        val plan = ListeningStatsSeed.plan(now)

        assertEquals(36, plan.size)
        assertEquals(12, plan.map { it.song.videoId }.distinct().size)
        assertEquals(6, plan.map { it.song.artist }.distinct().size)
        assertTrue(plan.groupBy { it.song.videoId }.values.all { it.size == 3 })
    }

    @Test
    fun `every artist has at least three plays`() {
        val plan = ListeningStatsSeed.plan(now)

        assertTrue(plan.groupBy { it.song.artist }.values.all { it.size >= 3 })
    }

    @Test
    fun `plays span the last sixty days with recent and rediscoverable rows`() {
        val plan = ListeningStatsSeed.plan(now)

        assertTrue(plan.all { it.playedAtMs in (now - 60 * dayMs)..now })
        assertTrue(plan.any { now - it.playedAtMs < dayMs })
        assertTrue(plan.any { now - it.playedAtMs > 14 * dayMs })
    }

    @Test
    fun `the plan is a pure function of now`() {
        assertEquals(ListeningStatsSeed.plan(now), ListeningStatsSeed.plan(now))
    }
}
