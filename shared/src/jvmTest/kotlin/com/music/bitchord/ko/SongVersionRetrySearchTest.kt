package com.music.bitchord.ko

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One in-flight search per video id: the post-audio retry may spend a fresh
 * search only on a provisional miss. A completed "no version" answer is the
 * verdict and used to be searched a third time within the same five seconds.
 */
class SongVersionRetrySearchTest {

    @Test
    fun `a definitive miss is not searched again`() {
        assertFalse(retrySearchAfterJoinNeeded(retryTimedOut = true, found = false, definitive = true))
    }

    @Test
    fun `a definitive miss from a joined lookup still earns the one fresh search`() {
        // The lookup another caller started may have been cut short (Sol I1):
        // without this search the MV that is already playing never gets its late version.
        assertTrue(retrySearchAfterJoinNeeded(retryTimedOut = true, found = false, definitive = true, joined = true))
    }

    @Test
    fun `a provisional miss earns the one fresh search`() {
        assertTrue(retrySearchAfterJoinNeeded(retryTimedOut = true, found = false, definitive = false))
    }

    @Test
    fun `a found version is already the answer`() {
        assertFalse(retrySearchAfterJoinNeeded(retryTimedOut = true, found = true, definitive = true))
    }

    @Test
    fun `a caller that is not the retry never searches`() {
        assertFalse(retrySearchAfterJoinNeeded(retryTimedOut = false, found = false, definitive = false))
    }
}
