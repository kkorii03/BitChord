package com.music.bitchord.ko

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis-head wait is event-driven: it wakes on the cache's own span
 * event, on a round finishing, and when a reader releases the entry.
 */
class KoAnalysisHeadEngineTest {

    private class RecordingCache {
        val listenerAdded = CompletableDeferred<Cache.Listener>()

        val cache: Cache = Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { _, method, args ->
            if (method.name == "addListener") {
                listenerAdded.complete(args[1] as Cache.Listener)
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Long::class.javaPrimitiveType -> 0L
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        } as Cache
    }

    private fun engine(
        cache: Cache,
        now: () -> Long = { 0L },
        anotherWriter: () -> Boolean = { false },
        headComplete: () -> Boolean = { false },
        inFlight: MutableSet<String> = mutableSetOf(),
    ) = KoAnalysisHeadEngine(
        cache = { cache },
        scope = CoroutineScope(Dispatchers.Default),
        upstreamAvailable = { false },
        keyFactory = CacheKeyFactory.DEFAULT,
        analysisHeads = mutableMapOf(),
        deferredLogged = mutableSetOf(),
        heldElsewhere = mutableSetOf(),
        inFlight = inFlight,
        stalled = mutableSetOf(),
        wantMap = mutableMapOf(),
        anotherWriterHas = { anotherWriter() },
        alignmentPrefixBytes = { 0L },
        alignmentHeadComplete = { headComplete() },
        analysisHeadSize = { it },
        fetchRange = { _, _, _, _, _ -> },
        recordContentLength = { _, _ -> },
        invalidateRenditionKeys = {},
        forgetPurgeCandidate = {},
        waitKeys = { listOf(it) },
        now = now,
    )

    @Test
    fun `a cache span event wakes the head wait`() = runBlocking {
        var complete = false
        val recorder = RecordingCache()
        val engine = engine(recorder.cache, headComplete = { complete })

        val result = async(Dispatchers.Default) { engine.awaitAnalysisHeadFor("abc", 10_000L) }
        val listener = withTimeout(2_000L) { recorder.listenerAdded.await() }
        complete = true
        listener.onSpanAdded(recorder.cache, CacheSpan("abc", 0L, 0L))

        assertTrue(withTimeout(2_000L) { result.await() })
    }

    @Test
    fun `a reader release wakes the head wait without waiting out the deadline`() = runBlocking {
        var writer = true
        var complete = false
        val recorder = RecordingCache()
        val engine = engine(
            recorder.cache,
            anotherWriter = { writer },
            headComplete = { complete },
        )

        val result = async(Dispatchers.Default) { engine.awaitAnalysisHeadFor("abc", 10_000L) }
        withTimeout(2_000L) { recorder.listenerAdded.await() }
        writer = false
        complete = true
        engine.retryAnalysisHeadIfReaderReleased("abc")

        assertTrue(withTimeout(2_000L) { result.await() })
    }

    @Test
    fun `a finishing round wakes awaitRoundDone`() = runBlocking {
        val inFlight = mutableSetOf("abc")
        val engine = engine(RecordingCache().cache, inFlight = inFlight)

        val result = async(Dispatchers.Default) { engine.awaitRoundDone("abc", 10_000L) }
        delay(50L)
        engine.finishRound("abc")

        withTimeout(2_000L) { result.await() }
        assertFalse("abc" in inFlight)
    }
}
