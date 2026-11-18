package com.music.bitchord.ko

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * oom2: 11-19 02:08:50 and 02:36:16 the app died with OutOfMemoryError thrown
 * by the innertubex cipher solve inside the next-url ahead resolve. Being an
 * Error, it went past `catch (e: Exception)` and out of the root launch, which
 * on Android kills the process. On the old code the handler below receives
 * the OutOfMemoryError and the first test fails.
 */
class KoNextUrlAheadOomTest {
    private val original = KoNextUrlAhead.resolve
    private val escaped = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e })

    @Before
    fun reset() = KoNextUrlAhead.reset()

    @After
    fun restore() {
        KoNextUrlAhead.reset()
        KoNextUrlAhead.resolve = original
        scope.cancel()
    }

    private suspend fun drainLaunched() {
        withTimeout(5_000) { scope.coroutineContext.job.children.toList().forEach { it.join() } }
    }

    @Test
    fun anOutOfMemoryErrorAheadIsNoStreamNotACrash() = runBlocking {
        val asked = CompletableDeferred<String>()
        KoNextUrlAhead.resolve = { id ->
            asked.complete(id)
            throw OutOfMemoryError("Failed to allocate a 7907344 byte allocation")
        }
        KoNextUrlAhead.onTick(scope, "A", 0, 0L, "B", "vB")
        KoNextUrlAhead.onTick(scope, "A", 10_000, 0L, "B", "vB")
        assertEquals("vB", withTimeout(5_000) { asked.await() })
        drainLaunched()
        assertTrue("an Error escaped the ahead resolve: $escaped", escaped.isEmpty())

        // Still working afterwards: the next pair resolves ahead as usual.
        val second = CompletableDeferred<String>()
        KoNextUrlAhead.resolve = { id -> second.complete(id) }
        KoNextUrlAhead.onTick(scope, "B", 0, 0L, "C", "vC")
        KoNextUrlAhead.onTick(scope, "B", 10_000, 0L, "C", "vC")
        assertEquals("vC", withTimeout(5_000) { second.await() })
        drainLaunched()
        assertTrue(escaped.isEmpty())
    }

    @Test
    fun cancellingTheAheadResolveStillCancels() = runBlocking {
        // The Throwable catch must not swallow cancellation: the drop still stops the wait.
        val stopped = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
        KoNextUrlAhead.resolve = {
            asked.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        KoNextUrlAhead.onTick(scope, "A", 0, 0L, "B", "vB")
        KoNextUrlAhead.onTick(scope, "A", 10_000, 0L, "B", "vB")
        withTimeout(5_000) { asked.await() }
        KoNextUrlAhead.keepOnly(setOf("A", "X"))
        withTimeout(5_000) { stopped.await() }
        drainLaunched()
        assertTrue(escaped.isEmpty())
    }
}
