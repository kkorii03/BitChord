package com.music.bitchord.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata resolution is a playback lease: a queue transition while the screen
 * is off must be able to open TDLib, and overlapping lookups or streams must
 * not close it until the last one is done.
 */
class TelegramStreamLeaseTest {

    @Test
    fun streamLeaseOpensTheClientInTheBackground() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginStreamLease())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endStreamLease())
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun overlappingLeasesKeepTheClientOpenUntilTheLastRelease() {
        val gate = TelegramClientGate()
        gate.onForeground(false)
        assertTrue(gate.beginStreamLease())
        assertFalse(gate.beginStreamLease())
        assertTrue(gate.shouldBeOpen)
        assertFalse(gate.endStreamLease())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endStreamLease())
        assertFalse(gate.shouldBeOpen)
    }

    // Review 11-17 (LEFT-2): Media3 cancels a Telegram open by interrupting the
    // loader thread. The wait for the client worker swallowed that interrupt, so
    // the reader that followed still fetched a 4 MB window for a skipped track.
    @Test
    fun waitingForTheWorkerKeepsTheCallersInterrupt() {
        val stillInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiter = Thread {
            Thread.currentThread().interrupt()
            awaitWorker(java.util.concurrent.CompletableFuture<Unit>(), 5)
            stillInterrupted.set(Thread.currentThread().isInterrupted)
        }
        waiter.start()
        waiter.join(10_000)
        assertFalse(waiter.isAlive)
        assertTrue(stillInterrupted.get())
    }
}
