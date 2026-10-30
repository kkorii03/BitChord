package com.music.bitchord.download

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Idle download workers wait on a queue revision, not a 100 ms poll. Every
 * waiting worker must see a change, and a quiet queue must time out instead of
 * waking on its own.
 */
class DownloadWorkWakeTest {

    @Test
    fun `a queue change wakes every idle worker`() = runTest {
        val seen = Downloads.workRevision()
        val first = async {
            Downloads.awaitWorkChange(seen)
            true
        }
        val second = async {
            Downloads.awaitWorkChange(seen)
            true
        }
        runCurrent()
        Downloads.wakeWorkers()
        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test
    fun `waiting times out when the queue does not change`() = runTest {
        val seen = Downloads.workRevision()
        assertFalse(Downloads.awaitWorkChange(seen, timeoutMs = 100L))
    }
}
