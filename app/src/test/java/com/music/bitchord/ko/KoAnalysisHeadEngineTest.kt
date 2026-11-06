package com.music.bitchord.ko

import android.net.FakeUri
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        upstreamAvailable: () -> Boolean = { false },
        now: () -> Long = { 0L },
        anotherWriter: (String) -> Boolean = { false },
        headComplete: () -> Boolean = { false },
        inFlight: MutableSet<String> = mutableSetOf(),
        analysisHeads: MutableMap<String, Boolean> = mutableMapOf(),
    ) = KoAnalysisHeadEngine(
        cache = { cache },
        scope = scope,
        upstreamAvailable = upstreamAvailable,
        keyFactory = CacheKeyFactory.DEFAULT,
        analysisHeads = analysisHeads,
        deferredLogged = mutableSetOf(),
        heldElsewhere = mutableSetOf(),
        inFlight = inFlight,
        stalled = mutableSetOf(),
        wantMap = mutableMapOf(),
        anotherWriterHas = anotherWriter,
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

    /**
     * A track playing from an `#alt`/`#hifi` rendition holds the *video*, not
     * the plain key the analysis head writes. Deferring on the video alone kept
     * the one-round claim unstarted for the whole song even though the two
     * entries never collide.
     */
    @Test
    fun `a player holding only another rendition does not defer the analysis head`() = runBlocking {
        val tracker = KoPlaybackReadTracker()
        tracker.retainVideo("abc")
        // The player is reading the substituted/lossless rendition, not the
        // plain key the analysis head writes.
        tracker.retainKey("abc#alt")
        tracker.retainKey("abc#hifi")
        val heads = mutableMapOf<String, Boolean>()
        // The claim is what this test checks; a cancelled scope keeps the
        // launched fetch from touching the network after the assertion.
        val scope = CoroutineScope(Dispatchers.Default).apply { cancel() }
        val engine = engine(
            cache = RecordingCache().cache,
            scope = scope,
            upstreamAvailable = { true },
            anotherWriter = { tracker.anotherWriterHas(it) },
            analysisHeads = heads,
        )

        engine.requestAnalysisHead(FakeUri("bitchord://watch?v=abc"))

        assertTrue(
            "the round must start while only another rendition is held",
            heads.containsKey("abc"),
        )
        tracker.releaseKey("abc#alt")
        tracker.releaseKey("abc#hifi")
        tracker.releaseVideo("abc")
    }

    /** Read-ahead's write to the analysis key is still a writer it waits for. */
    @Test
    fun `read-ahead writing the analysis key still defers the analysis head`() = runBlocking {
        val tracker = KoPlaybackReadTracker()
        tracker.retainReadAhead("abc")
        val heads = mutableMapOf<String, Boolean>()
        val engine = engine(
            cache = RecordingCache().cache,
            upstreamAvailable = { true },
            anotherWriter = { tracker.anotherWriterHas(it) },
            analysisHeads = heads,
        )

        engine.requestAnalysisHead(FakeUri("bitchord://watch?v=abc"))

        assertFalse("read-ahead keeps the round deferred", heads.containsKey("abc"))
        tracker.releaseReadAhead("abc")
    }

    /** Read-ahead's `#alt`/`#hifi` write is a different entry, so it does not block the analysis key. */
    @Test
    fun `read-ahead writing another rendition does not defer the analysis head`() = runBlocking {
        val tracker = KoPlaybackReadTracker()
        tracker.retainReadAhead("abc#alt")
        val heads = mutableMapOf<String, Boolean>()
        val scope = CoroutineScope(Dispatchers.Default).apply { cancel() }
        val engine = engine(
            cache = RecordingCache().cache,
            scope = scope,
            upstreamAvailable = { true },
            anotherWriter = { tracker.anotherWriterHas(it) },
            analysisHeads = heads,
        )

        engine.requestAnalysisHead(FakeUri("bitchord://watch?v=abc"))

        assertTrue("the analysis key is free", heads.containsKey("abc"))
        tracker.releaseReadAhead("abc#alt")
    }

    /** The same player on the analysis key itself still defers the round. */
    @Test
    fun `a player reading the analysis key defers the analysis head`() = runBlocking {
        val tracker = KoPlaybackReadTracker()
        tracker.retainVideo("abc")
        tracker.retainKey("abc")
        val heads = mutableMapOf<String, Boolean>()
        val inFlight = mutableSetOf<String>()
        val engine = engine(
            cache = RecordingCache().cache,
            upstreamAvailable = { true },
            anotherWriter = { tracker.anotherWriterHas(it) },
            inFlight = inFlight,
            analysisHeads = heads,
        )

        engine.requestAnalysisHead(FakeUri("bitchord://watch?v=abc"))

        assertFalse("the round must wait for the player's entry", heads.containsKey("abc"))
        assertFalse("no round may be in flight", "abc" in inFlight)
        tracker.releaseKey("abc")
        tracker.releaseVideo("abc")
    }
}
