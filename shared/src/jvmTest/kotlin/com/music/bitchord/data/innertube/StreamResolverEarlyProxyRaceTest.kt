package com.music.bitchord.data.innertube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The early proxy beside a still-running InnerTubeX walk: a proxy timeout is
 * not an InnerTubeX verdict, so the walk must survive for the extraction
 * fallback instead of throwing [kotlinx.coroutines.CancellationException].
 */
class StreamResolverEarlyProxyRaceTest {

    @Test
    fun `a proxy timeout leaves InnerTubeX alive for the extraction fallback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val itx = CompletableDeferred<String?>()
        try {
            val early = StreamResolver.raceEarlyProxy(
                scope = scope,
                videoId = "early-proxy-timeout",
                itx = itx,
                proxyTimeoutMs = 100L,
                proxy = {
                    delay(1_000)
                    "proxy-stream"
                },
            )

            assertTrue(early.timedOut)
            assertNull(early.value)
            assertTrue(
                itx.isActive,
                "a proxy timeout must not cancel the still-running InnerTubeX walk",
            )

            // This is the resolver's own next step after a non-refusal miss:
            // InnerTubeX answers nothing, so the caller reaches extraction.
            itx.complete(null)
            val fallback = raceDirectDefinitive(
                direct = CompletableDeferred(
                    DirectProbe.Other(refused = false, reason = null),
                ),
                innerTubeX = itx,
            )
            assertTrue(fallback is DirectVerdictRace.None)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a proxy win still cancels the InnerTubeX walk`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val itx = CompletableDeferred<String?>()
        try {
            val early = StreamResolver.raceEarlyProxy(
                scope = scope,
                videoId = "early-proxy-win",
                itx = itx,
                proxyTimeoutMs = 1_000L,
                proxy = { "proxy-stream" },
            )

            assertEquals("proxy", early.route)
            assertEquals("proxy-stream", early.value)
            assertTrue(itx.isCancelled)
        } finally {
            scope.cancel()
        }
    }
}
