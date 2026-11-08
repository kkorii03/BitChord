package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A proxy leg that outlives its early-race window keeps racing the rest of the
 * walk instead of being thrown away (rs18-rs20, both builds: 71 closed windows
 * whose proxy answer landed 0.1-2.6 s later; rs20 171a WdiSosDz4ss had a proxy
 * stream at 3.9 s and its first sound at 27.4 s).
 *
 * The timings here are the logged case scaled down: window 150 ms, proxy
 * answer 100 ms after it, InnerTubeX walk answering much later.
 */
class LateProxyLegTest {

    private val legScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun leg(answerAtMs: Long, value: String?, asked: AtomicInteger? = null): Deferred<String?> =
        legScope.async {
            asked?.incrementAndGet()
            delay(answerAtMs)
            value
        }

    /** The resolver's site: the window races the walk and the leg; then the late race. */
    private suspend fun start(
        windowMs: Long,
        walk: Deferred<String?>,
        proxyLeg: Deferred<String?>,
    ): Pair<String, String?> {
        val raced = withTimeoutOrNull(windowMs) {
            raceForFirstSuccess(direct = { walk.await() }, proxy = { proxyLeg.await() })
        }
        val won = raced?.second
        if (raced != null && won != null) {
            if (raced.first == "proxy") walk.cancel() else proxyLeg.cancel()
            return "window:${raced.first}" to won
        }
        val kept = proxyLeg.takeIf {
            BlockedStartPolicy.keepProxyAfterWindow(
                windowAnswered = false,
                legActive = it.isActive,
                legAnswered = it.isCompleted && !it.isCancelled && it.getCompleted() != null,
            )
        }
        val rest: suspend () -> String? = { walk.await() }
        return when (val late = raceLateLeg(kept, rest)) {
            is LateLegRace.Late -> {
                walk.cancel()
                "late" to late.value
            }
            is LateLegRace.Rest -> "walk" to late.value
        }
    }

    @Test
    fun `a proxy answer that lands after its window beats the slow walk`() = runBlocking<Unit> {
        val walk = leg(answerAtMs = 3_000, value = "walk-url")
        val proxy = leg(answerAtMs = 250, value = "proxy-url")
        val began = System.nanoTime()
        val result = start(windowMs = 150, walk = walk, proxyLeg = proxy)
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertEquals("late" to "proxy-url", result)
        assertTrue(tookMs < 1_500, "the late proxy answer is played at once, not after the walk ($tookMs ms)")
        assertTrue(walk.isCancelled, "the walk that lost is stopped")
    }

    @Test
    fun `a proxy answer inside the window is unchanged and asks nothing more`() = runBlocking<Unit> {
        val asked = AtomicInteger()
        val walk = leg(answerAtMs = 3_000, value = "walk-url")
        val proxy = leg(answerAtMs = 50, value = "proxy-url", asked = asked)
        assertEquals("window:proxy" to "proxy-url", start(windowMs = 150, walk = walk, proxyLeg = proxy))
        assertEquals(1, asked.get(), "one proxy request, as before")
        assertTrue(walk.isCancelled)
    }

    @Test
    fun `a walk that answers inside the window stops the leg`() = runBlocking<Unit> {
        val walk = leg(answerAtMs = 20, value = "walk-url")
        val proxy = leg(answerAtMs = 2_000, value = "proxy-url")
        assertEquals("window:direct" to "walk-url", start(windowMs = 150, walk = walk, proxyLeg = proxy))
        assertTrue(proxy.isCancelled, "a leg the walk beat must not keep asking")
    }

    @Test
    fun `a walk that answers after the window but before the late leg wins and stops the leg`() = runBlocking<Unit> {
        val walk = leg(answerAtMs = 200, value = "walk-url")
        val proxy = leg(answerAtMs = 2_000, value = "proxy-url")
        assertEquals("walk" to "walk-url", start(windowMs = 100, walk = walk, proxyLeg = proxy))
        assertTrue(proxy.isCancelled)
    }

    @Test
    fun `a late leg that fails leaves the walk exactly as before`() = runBlocking<Unit> {
        val walk = leg(answerAtMs = 400, value = "walk-url")
        val proxy = leg(answerAtMs = 200, value = null)
        assertEquals("walk" to "walk-url", start(windowMs = 100, walk = walk, proxyLeg = proxy))
    }

    @Test
    fun `a walk that finds nothing still gets the late leg's stream`() = runBlocking<Unit> {
        val walk = leg(answerAtMs = 150, value = null)
        val proxy = leg(answerAtMs = 400, value = "proxy-url")
        assertEquals("late" to "proxy-url", start(windowMs = 100, walk = walk, proxyLeg = proxy))
    }

    @Test
    fun `both failing is no stream`() = runBlocking<Unit> {
        val walk = leg(answerAtMs = 150, value = null)
        val proxy = leg(answerAtMs = 200, value = null)
        assertEquals("walk" to null, start(windowMs = 100, walk = walk, proxyLeg = proxy))
    }

    @Test
    fun `with no walk left the late leg alone is awaited`() = runBlocking<Unit> {
        val proxy = leg(answerAtMs = 150, value = "proxy-url")
        val outcome = raceLateLeg<String>(proxy, null)
        assertTrue(outcome is LateLegRace.Late && outcome.value == "proxy-url")
        val failed = raceLateLeg<String>(leg(answerAtMs = 50, value = null), null)
        assertTrue(failed is LateLegRace.Rest && failed.value == null)
    }

    @Test
    fun `no late leg runs the rest alone and hands on its exception`() = runBlocking<Unit> {
        val walked: suspend () -> String? = { "walk-url" }
        val outcome = raceLateLeg(null, walked)
        assertTrue(outcome is LateLegRace.Rest && outcome.value == "walk-url")
        val gone: suspend () -> String? = { throw IllegalStateException("gone") }
        assertFailsWith<IllegalStateException> { raceLateLeg(null, gone) }
    }

    @Test
    fun `a verdict from the walk ends the start and stops the leg`() = runBlocking<Unit> {
        val proxy = leg(answerAtMs = 2_000, value = "proxy-url")
        val gone: suspend () -> String? = {
            delay(50)
            throw IllegalStateException("the recording is gone")
        }
        assertFailsWith<IllegalStateException> { raceLateLeg(proxy, gone) }
        assertTrue(proxy.isCancelled, "a verdict about the recording does not wait for the proxy")
    }

    @Test
    fun `a track change stops both the late leg and the walk`() = runBlocking<Unit> {
        val proxyStopped = CompletableDeferred<Unit>()
        val walkStopped = AtomicBoolean(false)
        val proxy = legScope.async<String?> {
            try {
                awaitCancellation()
            } finally {
                proxyStopped.complete(Unit)
            }
        }
        val walkStarted = CompletableDeferred<Unit>()
        val walk: suspend () -> String? = {
            walkStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                walkStopped.set(true)
            }
        }
        val resolve = launch(Dispatchers.IO) { raceLateLeg(proxy, walk) }
        walkStarted.await()
        resolve.cancelAndJoin()
        withTimeoutOrNull(1_000) { proxyStopped.await() }
        assertTrue(proxyStopped.isCompleted, "the late leg must not outlive the resolve")
        delay(100)
        assertTrue(walkStopped.get(), "the walk must not outlive the resolve")
    }

    @Test
    fun `a late leg that won does not let the walk keep working`() = runBlocking<Unit> {
        val walkFinished = AtomicBoolean(false)
        val proxy = leg(answerAtMs = 30, value = "proxy-url")
        val walk: suspend () -> String? = {
            delay(300)
            walkFinished.set(true)
            "walk-url"
        }
        val outcome = raceLateLeg(proxy, walk)
        assertTrue(outcome is LateLegRace.Late && outcome.value == "proxy-url")
        delay(500)
        assertFalse(walkFinished.get(), "the losing walk is cancelled")
    }

    @Test
    fun `the leg survives its window only while it can still answer`() {
        assertFalse(BlockedStartPolicy.keepProxyAfterWindow(windowAnswered = true, legActive = true, legAnswered = false))
        assertFalse(BlockedStartPolicy.keepProxyAfterWindow(windowAnswered = true, legActive = false, legAnswered = true))
        assertFalse(BlockedStartPolicy.keepProxyAfterWindow(windowAnswered = false, legActive = false, legAnswered = false))
        assertTrue(BlockedStartPolicy.keepProxyAfterWindow(windowAnswered = false, legActive = true, legAnswered = false))
        assertTrue(BlockedStartPolicy.keepProxyAfterWindow(windowAnswered = false, legActive = false, legAnswered = true))
    }

    @Test
    fun `the leg's ceiling is bounded and covers the logged late answers`() {
        // Outside the gate the window is unbounded and so is the leg, as before.
        assertEquals(Long.MAX_VALUE, BlockedStartPolicy.lateProxyCeilingMs(Long.MAX_VALUE))
        assertEquals(0L, BlockedStartPolicy.lateProxyCeilingMs(0L))
        val window = BlockedStartPolicy.PROXY_TIMEOUT_MS
        val ceiling = BlockedStartPolicy.lateProxyCeilingMs(window)
        // rs18-rs20: late answers 93-2078 ms after the window on 11 of 13 rows.
        assertTrue(ceiling - window >= 2_078, "the grace keeps the logged late answers")
        // The longest window a gated start can get still leaves the rescue room.
        assertTrue(
            BlockedStartPolicy.lateProxyCeilingMs(BlockedStartPolicy.RESOLVER_BUDGET_MS) <=
                BlockedStartPolicy.TOTAL_BUDGET_MS,
        )
    }

    @Test
    fun `a leg that answered as the window shut is adopted, not dropped`() = runBlocking<Unit> {
        val proxy = CompletableDeferred<String?>("proxy-url")
        val walk: suspend () -> String? = {
            delay(2_000)
            "walk-url"
        }
        val outcome = raceLateLeg(proxy, walk)
        assertTrue(outcome is LateLegRace.Late && outcome.value == "proxy-url")
        assertNull((outcome as? LateLegRace.Rest)?.value)
    }
}
