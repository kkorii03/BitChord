package com.music.bitchord.ko

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
}
