package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a play still only in the open month leads the recent tracks`() {
        val onDisk = TrackEntry(id = "old", title = "Old", last = 100)
        val justPlayed = TrackEntry(id = "just", title = "Just played", last = 500)

        val recent = ListeningStats.mergeRecentTracks(
            fileTracks = listOf(onDisk),
            openTracks = listOf(justPlayed),
            limit = 20,
        )

        assertEquals(listOf("just", "old"), recent.map { it.id })
    }

    @Test
    fun `the open month's copy wins a tie against the file`() {
        val file = TrackEntry(id = "a", title = "Old title", last = 300)
        val open = TrackEntry(id = "a", title = "New title", last = 300)

        val recent = ListeningStats.mergeRecentTracks(
            fileTracks = listOf(file),
            openTracks = listOf(open),
            limit = 20,
        )

        assertEquals(1, recent.size)
        assertEquals("New title", recent.single().title)
    }

    @Test
    fun `recent tracks are distinct by id and stop at the limit`() {
        val file = listOf(
            TrackEntry(id = "a", title = "A", last = 100),
            TrackEntry(id = "b", title = "B", last = 200),
        )
        val open = listOf(
            TrackEntry(id = "a", title = "A again", last = 400),
            TrackEntry(id = "c", title = "C", last = 300),
        )

        val recent = ListeningStats.mergeRecentTracks(file, open, limit = 2)

        assertEquals(listOf("a", "c"), recent.map { it.id })
        assertEquals("A again", recent.first().title)
    }

    @Test
    fun `a start is announced only on the first played step of an unannounced track`() {
        assertTrue(announcesRecentsStart(playedBeforeMs = 0L, announcedId = null, videoId = "v1"))
        assertTrue(announcesRecentsStart(playedBeforeMs = 0L, announcedId = "v0", videoId = "v1"))
        assertFalse(announcesRecentsStart(playedBeforeMs = 5_000L, announcedId = null, videoId = "v1"))
        assertFalse(announcesRecentsStart(playedBeforeMs = 0L, announcedId = "v1", videoId = "v1"))
    }
}
