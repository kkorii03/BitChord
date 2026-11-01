package com.music.bitchord.download

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A batch enqueues one track per call. Only the first may start the service;
 * later tracks are already in the queue that first start drains. A service
 * that actually went away has to open the gate again, or the next batch would
 * sit Queued with nothing to run it.
 */
class DownloadServiceStartGateTest {

    @After
    fun tearDown() {
        DownloadServiceStartGate.stopped()
    }

    @Test
    fun `the first start of a batch is requested and the rest are not`() {
        assertTrue(DownloadServiceStartGate.requestStart())
        assertFalse(DownloadServiceStartGate.requestStart())
        assertFalse(DownloadServiceStartGate.requestStart())
    }

    @Test
    fun `a stopped service lets the next enqueue start it again`() {
        assertTrue(DownloadServiceStartGate.requestStart())

        DownloadServiceStartGate.stopped()

        assertTrue(DownloadServiceStartGate.requestStart())
    }

    @Test
    fun `a refused start does not leave the gate closed forever`() {
        assertTrue(DownloadServiceStartGate.requestStart())

        DownloadServiceStartGate.startFailed()

        assertTrue(DownloadServiceStartGate.requestStart())
    }
}
