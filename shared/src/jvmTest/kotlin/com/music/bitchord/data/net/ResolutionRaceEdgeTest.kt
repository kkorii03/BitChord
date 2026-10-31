package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two-route race's failure paths: an exception is a failed route, the
 * surviving route still gets to answer, and a winner cancels the loser.
 */
class ResolutionRaceEdgeTest {

    @Test
    fun `a throwing direct route lets the proxy answer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.complete(Unit)
                throw IllegalStateException("direct failed")
            },
            proxy = {
                started.await()
                delay(20)
                "proxy-result"
            },
        )
        assertEquals("proxy" to "proxy-result", result)
    }

    @Test
    fun `a throwing proxy route lets the direct route answer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.await()
                delay(20)
                "direct-result"
            },
            proxy = {
                started.complete(Unit)
                throw IllegalStateException("proxy failed")
            },
        )
        assertEquals("direct" to "direct-result", result)
    }

    @Test
    fun `both throwing keeps the first failure as a null answer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.complete(Unit)
                throw IllegalStateException("direct failed")
            },
            proxy = {
                started.await()
                delay(20)
                throw IllegalStateException("proxy failed")
            },
        )
        assertEquals("direct" to null, result)
    }

    @Test
    fun `the first failure can come from the proxy`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.await()
                delay(50)
                "direct-result"
            },
            proxy = {
                started.complete(Unit)
                null
            },
        )
        assertEquals("direct" to "direct-result", result)
    }

    @Test
    fun `a winning route cancels the loser`() = runBlocking {
        val proxyFinished = AtomicBoolean(false)
        val result = raceForFirstSuccess<String>(
            direct = { "direct-result" },
            proxy = {
                delay(300)
                proxyFinished.set(true)
                "proxy-result"
            },
        )
        assertEquals("direct" to "direct-result", result)
        delay(500)
        assertFalse(proxyFinished.get(), "the losing route must not keep working after a win")
    }

    @Test
    fun `a caller's timeout while the second route is awaited stops both routes`() = runBlocking {
        val proxyCancelled = CompletableDeferred<Unit>()
        val result = withTimeoutOrNull(100L) {
            raceForFirstSuccess<String>(
                direct = { null },
                proxy = {
                    try {
                        awaitCancellation()
                    } finally {
                        proxyCancelled.complete(Unit)
                    }
                },
            )
        }
        assertNull(result, "the caller's budget expired before either route answered")
        withTimeoutOrNull(1_000L) { proxyCancelled.await() }
        assertTrue(
            proxyCancelled.isCompleted,
            "the still-running route must be cancelled, not left on the shared scope",
        )
    }
}
