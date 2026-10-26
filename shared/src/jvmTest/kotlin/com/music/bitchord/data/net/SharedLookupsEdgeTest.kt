package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The sharing rule's edges: different keys are independent, a failure is seen
 * by every waiter, and the in-flight entry is released so a later asker can
 * try again.
 */
class SharedLookupsEdgeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `different keys are not joined to each other`() = runBlocking {
        val lookups = SharedLookups<String>(scope)
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()

        val a = lookups.lookup("a") {
            releaseA.await()
            "answer-a"
        }
        val b = lookups.lookup("b") {
            releaseB.await()
            "answer-b"
        }

        assertFalse(a.joined)
        assertFalse(b.joined)
        assertEquals(2, lookups.runningCount())
        releaseA.complete(Unit)
        releaseB.complete(Unit)
        assertEquals("answer-a", a.result.await())
        assertEquals("answer-b", b.result.await())
    }

    @Test
    fun `a second asker receives the same deferred`() = runBlocking {
        val lookups = SharedLookups<String>(scope)
        val release = CompletableDeferred<Unit>()
        val first = lookups.lookup("same") {
            release.await()
            "answer"
        }
        val second = lookups.lookup("same") { "other" }

        assertTrue(second.joined)
        assertSame(first.result, second.result)
        release.complete(Unit)
        assertEquals("answer", second.result.await())
    }

    @Test
    fun `isRunning tracks the lookup until it completes`() = runBlocking {
        val lookups = SharedLookups<String>(scope)
        val release = CompletableDeferred<Unit>()
        val lookup = lookups.lookup("running") {
            release.await()
            "done"
        }

        assertTrue(lookups.isRunning("running"))
        assertEquals(1, lookups.runningCount())
        release.complete(Unit)
        assertEquals("done", lookup.result.await())
        withTimeout(5_000) {
            while (lookups.isRunning("running")) delay(5)
        }
        assertFalse(lookups.isRunning("running"))
        assertEquals(0, lookups.runningCount())
    }

    @Test
    fun `a failure reaches every waiter and is not cached`() = runBlocking {
        val lookups = SharedLookups<String>(scope)
        val release = CompletableDeferred<Unit>()
        val first = lookups.lookup("fails") {
            release.await()
            throw IllegalStateException("boom")
        }
        val second = lookups.lookup("fails") { "unreachable" }

        assertTrue(second.joined)
        release.complete(Unit)
        assertFailsWith<IllegalStateException> { first.result.await() }
        assertFailsWith<IllegalStateException> { second.result.await() }

        withTimeout(5_000) {
            while (lookups.isRunning("fails")) delay(5)
        }
        val retry = lookups.lookup("fails") { "recovered" }
        assertFalse(retry.joined)
        assertEquals("recovered", retry.result.await())
    }

    @Test
    fun `a completed lookup is started again rather than replayed`() = runBlocking {
        val lookups = SharedLookups<Int>(scope)
        assertEquals(1, lookups.lookup("once") { 1 }.result.await())
        assertEquals(2, lookups.lookup("once") { 2 }.result.await())
        assertEquals(3, lookups.lookup("once") { 3 }.result.await())
    }
}
