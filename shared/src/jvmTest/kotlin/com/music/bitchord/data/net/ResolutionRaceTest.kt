package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The early-race decision: the proxy walk runs beside the direct ladder and
 * the first route that produces a stream wins. If both fail the caller sees
 * one failure, not a cancellation.
 */
class ResolutionRaceTest {

    @Test
    fun `direct wins when it returns first`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.complete(Unit)
                "direct-result"
            },
            proxy = {
                started.await()
                delay(200)
                "proxy-result"
            },
        )
        assertEquals("direct" to "direct-result", result)
    }

    @Test
    fun `proxy wins when it returns first`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.complete(Unit)
                delay(200)
                "direct-result"
            },
            proxy = {
                started.await()
                "proxy-result"
            },
        )
        assertEquals("proxy" to "proxy-result", result)
    }

    @Test
    fun `the surviving route answers when the first result is a failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.complete(Unit)
                null
            },
            proxy = {
                started.await()
                delay(50)
                "proxy-result"
            },
        )
        assertEquals("proxy" to "proxy-result", result)
    }

    @Test
    fun `both failing keeps the first failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = raceForFirstSuccess<String>(
            direct = {
                started.complete(Unit)
                null
            },
            proxy = {
                started.await()
                delay(50)
                null
            },
        )
        assertEquals("direct" to null, result)
    }
}
