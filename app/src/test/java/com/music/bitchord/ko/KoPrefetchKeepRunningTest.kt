package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KO] P1-3: a change behind the next track must not restart its read-ahead. */
class KoPrefetchKeepRunningTest {

    @Test
    fun `a row appended behind the same next track keeps its running job`() {
        assertTrue(keepRunningReadAhead(listOf("A"), listOf("A", "B"), jobTarget = "A", jobActive = true))
        assertTrue(keepRunningReadAhead(listOf("A", "B"), listOf("A", "C"), jobTarget = "A", jobActive = true))
    }

    @Test
    fun `a new next track restarts`() {
        assertFalse(keepRunningReadAhead(listOf("A", "B"), listOf("B", "C"), jobTarget = "A", jobActive = true))
    }

    @Test
    fun `a finished or missing job restarts`() {
        assertFalse(keepRunningReadAhead(listOf("A"), listOf("A", "B"), jobTarget = "A", jobActive = false))
        assertFalse(keepRunningReadAhead(emptyList(), listOf("A", "B"), jobTarget = null, jobActive = false))
    }

    @Test
    fun `a job working on a later row restarts when that row may have changed`() {
        // Head is a download or source row: the job read ahead for row two.
        assertFalse(keepRunningReadAhead(listOf("D", "B"), listOf("D", "C"), jobTarget = "B", jobActive = true))
    }

    @Test
    fun `an emptied queue restarts`() {
        assertFalse(keepRunningReadAhead(listOf("A"), emptyList(), jobTarget = "A", jobActive = true))
    }

    // ---- RG3-1: a kept job still warms the URL of a changed row behind next

    @Test
    fun `RG3-1 a swapped row behind the kept next track is warmed`() {
        assertEquals(listOf("X"), readAheadWarmupDelta(listOf("A", "B", "C"), listOf("A", "X", "C"), lookaheadCount = 2))
        assertEquals(listOf("B"), readAheadWarmupDelta(listOf("A"), listOf("A", "B"), lookaheadCount = 2))
    }

    @Test
    fun `RG3-1 nothing new behind next means nothing to warm`() {
        assertEquals(emptyList<String>(), readAheadWarmupDelta(listOf("A", "B"), listOf("A", "B", "C"), lookaheadCount = 2))
        // Power saving or metered: only the next track, which is the job's own.
        assertEquals(emptyList<String>(), readAheadWarmupDelta(listOf("A", "B"), listOf("A", "X"), lookaheadCount = 1))
    }
}
