package com.music.bitchord.download

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue's wake-up signal: one change wakes every download worker, and an
 * idle wait is a timeout rather than a poll loop.
 */
class DownloadsWorkSignalTest {

    @Test
    fun `one signal wakes every worker waiting on the same generation`() = runTest {
        val since = Downloads.workVersion()
        val waiters = List(4) { async { Downloads.awaitWorkChange(since, 5_000L) } }
        runCurrent()

        Downloads.signalWork()

        waiters.forEach { assertNotNull(it.await()) }
    }

    @Test
    fun `a wait with no change returns null after its timeout`() = runTest {
        val since = Downloads.workVersion()
        assertNull(Downloads.awaitWorkChange(since, 100L))
    }
}
