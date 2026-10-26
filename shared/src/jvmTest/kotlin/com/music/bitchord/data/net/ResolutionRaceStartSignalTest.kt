package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lazy half of the early race: the proxy waits for the direct ladder's
 * first refusal, but a direct failure without one still gets the proxy tried.
 */
class ResolutionRaceStartSignalTest {

    @Test
    fun `the proxy starts on the first refusal and can win`() = runBlocking {
        val firstRefusal = CompletableDeferred<Unit>()
        val proxyStarted = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                delay(20)
                firstRefusal.complete(Unit)
                delay(500)
                "direct-result"
            },
            proxy = {
                proxyStarted.complete(Unit)
                "proxy-result"
            },
            proxyStart = firstRefusal,
        )
        assertEquals("proxy" to "proxy-result", result)
        assertTrue(proxyStarted.isCompleted, "the proxy must start as soon as the refusal is signalled")
    }

    @Test
    fun `the proxy does not start when direct answers before any refusal`() = runBlocking {
        val proxyStarted = AtomicBoolean(false)
        val result = raceForFirstSuccess<String>(
            direct = { "direct-result" },
            proxy = {
                proxyStarted.set(true)
                "proxy-result"
            },
            proxyStart = CompletableDeferred(),
        )
        assertEquals("direct" to "direct-result", result)
        delay(100)
        assertFalse(proxyStarted.get(), "a playable direct answer must not spend a proxy request")
    }

    @Test
    fun `a direct failure without a refusal still starts the proxy`() = runBlocking {
        val result = raceForFirstSuccess<String>(
            direct = { null },
            proxy = { "proxy-result" },
            proxyStart = CompletableDeferred(),
        )
        assertEquals("proxy" to "proxy-result", result)
    }
}
