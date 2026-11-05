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

    @Test
    fun `a walk that answers inside the fallback is played without waiting for extraction`() =
        runBlocking {
            // The 11-05 gated start: the walk outlived its window but was one
            // request from answering. Raced with extraction, its answer is the
            // one that plays.
            val result = raceForFirstSuccess<String>(
                direct = {
                    delay(20)
                    "walk-url"
                },
                proxy = {
                    delay(5_000)
                    "extraction-url"
                },
            )
            assertEquals("direct" to "walk-url", result)
        }

    @Test
    fun `the first answer is kept and a late walk never replaces it`() = runBlocking {
        // Extraction answered first, so the walk's later answer must not be
        // adopted over a stream that has already been handed on: the loser is
        // stopped, and nothing re-resolves the row after this returns.
        val lateWalkAnswered = AtomicBoolean(false)
        val result = raceForFirstSuccess<String>(
            direct = {
                delay(200)
                lateWalkAnswered.set(true)
                "late-walk-url"
            },
            proxy = {
                delay(20)
                "extraction-url"
            },
        )
        assertEquals("proxy" to "extraction-url", result)
        delay(400)
        assertFalse(
            lateWalkAnswered.get(),
            "the late walk is dropped, not adopted over the stream already returned",
        )
    }
}
