package com.music.bitchord.data.innertube

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bounded proxy race beside the InnerTubeX walk. An expired proxy window
 * leaves the walk for the final race, but a track settled as unplayable must
 * not keep extracting after it has been declared dead.
 */
class ProxyEarlyRaceTest {

    @Test
    fun `a proxy timeout that skips extraction cancels the InnerTubeX walk`() = runBlocking {
        val walkStopped = AtomicBoolean(false)
        val itx = async {
            try {
                delay(10_000)
                "itx-stream"
            } finally {
                walkStopped.set(true)
            }
        }

        val outcome = raceProxyEarly(
            itx = itx,
            scope = this,
            proxyTimeoutMs = 50L,
            skipExtraction = true,
            resolveProxy = { null },
            isProxyDown = { false },
            unplayableReason = { "blocked" },
        )

        assertIs<ProxyEarlyRace.Unplayable>(outcome)
        assertEquals("blocked", outcome.reason)
        assertTrue(itx.isCancelled, "the walk must not outlive a track declared unplayable")
        itx.join()
        assertTrue(walkStopped.get())
    }

    @Test
    fun `a proxy timeout leaves the InnerTubeX walk running for the final race`() = runBlocking {
        val itx = CompletableDeferred<String?>()

        val outcome = raceProxyEarly(
            itx = itx,
            scope = this,
            proxyTimeoutMs = 50L,
            skipExtraction = false,
            resolveProxy = { null },
            isProxyDown = { false },
            unplayableReason = { "blocked" },
        )

        assertIs<ProxyEarlyRace.Inconclusive>(outcome)
        assertFalse(itx.isCancelled, "the final race still has to consume this walk")
        itx.complete("late-stream")
        assertEquals("late-stream", itx.await())
    }

    @Test
    fun `the proxy winning cancels the InnerTubeX walk`() = runBlocking {
        val itx = CompletableDeferred<String?>()

        val outcome = raceProxyEarly(
            itx = itx,
            scope = this,
            proxyTimeoutMs = 1_000L,
            skipExtraction = false,
            resolveProxy = { "proxy-stream" },
            isProxyDown = { false },
            unplayableReason = { "blocked" },
        )

        assertIs<ProxyEarlyRace.Won<String>>(outcome)
        assertEquals("proxy", outcome.route)
        assertEquals("proxy-stream", outcome.value)
        assertTrue(itx.isCancelled)
    }

    @Test
    fun `a dead proxy cancels the walk and is returned as transient`() = runBlocking {
        val itx = CompletableDeferred<String?>()
        val dead = IOException("proxy unreachable")

        val outcome = raceProxyEarly(
            itx = itx,
            scope = this,
            proxyTimeoutMs = 2_000L,
            skipExtraction = true,
            resolveProxy = { throw dead },
            isProxyDown = { it.message == "proxy unreachable" },
            unplayableReason = { "blocked" },
        )

        assertIs<ProxyEarlyRace.ProxyDown>(outcome)
        assertEquals("proxy unreachable", outcome.failure.message)
        assertTrue(itx.isCancelled)
    }
}
