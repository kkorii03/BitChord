package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that stops one track being looked up twice: the queue build and
 * the service's pre-audio ask share the lookup in flight, and a caller that
 * times out leaves the work (and its cache entry) behind for the next asker.
 */
class SharedLookupsTest {

    @Test
    fun `a second asker joins the lookup already in flight`() = runBlocking {
        val lookups = SharedLookups<String>(CoroutineScope(Dispatchers.IO))
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = lookups.lookup("v1") {
            calls.incrementAndGet()
            release.await()
            "answer"
        }
        val second = lookups.lookup("v1") {
            calls.incrementAndGet()
            "second"
        }

        assertFalse(first.joined, "the first asker starts the lookup")
        assertTrue(second.joined, "the second asker waits on it")
        release.complete(Unit)
        assertEquals("answer", first.result.await())
        assertEquals("answer", second.result.await())
        assertEquals(1, calls.get(), "the work is done once")
    }

    @Test
    fun `a caller that gives up does not cancel the shared lookup`() = runBlocking {
        val lookups = SharedLookups<String>(CoroutineScope(Dispatchers.IO))
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<String>()

        val timedOut = runCatching {
            withTimeout(50) {
                lookups.lookup("v2") {
                    started.complete(Unit)
                    delay(250)
                    "late-answer".also { finished.complete(it) }
                }.result.await()
            }
        }

        assertTrue(
            timedOut.exceptionOrNull() is TimeoutCancellationException,
            "the waiter's own budget ends the wait",
        )
        assertEquals("late-answer", withTimeout(5_000) { finished.await() })
        assertFalse(lookups.isRunning("v2"), "the finished lookup leaves the way clear")
    }

    @Test
    fun `a finished lookup can be started again`() = runBlocking {
        val lookups = SharedLookups<Int>(CoroutineScope(Dispatchers.IO))
        val quick = lookups.lookup("v3") { 1 }
        assertEquals(1, quick.result.await())
        val again = lookups.lookup("v3") { 2 }
        assertFalse(again.joined)
        assertEquals(2, again.result.await())
    }
}
