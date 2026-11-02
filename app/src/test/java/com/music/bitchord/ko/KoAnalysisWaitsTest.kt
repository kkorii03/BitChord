package com.music.bitchord.ko

import androidx.media3.datasource.cache.Cache
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analysis waits are event-driven: the whole-track wait parks on the
 * round's completion signal (the old 100 ms poll is gone), and every rendition
 * suffix the cache can write is watched.
 */
class KoAnalysisWaitsTest {

    private val cacheStub = Proxy.newProxyInstance(
        Cache::class.java.classLoader,
        arrayOf(Cache::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Long::class.javaPrimitiveType -> 0L
            Int::class.javaPrimitiveType -> 0
            else -> null
        }
    } as Cache

    @Test
    fun `wait keys cover the plain and every substituted rendition`() {
        val keys = analysisWaitKeys(
            videoId = "abc123",
            altSuffix = "#alt",
            renditionKeys = listOf("abc123", "abc123#hifi", "abc123#alt-noatmos", "abc123#watch"),
        )

        assertTrue(keys.contains("abc123"))
        assertTrue(keys.contains("abc123#alt"))
        assertTrue(keys.contains("abc123#alt-noatmos"))
        assertTrue(keys.contains("abc123#hifi"))
        assertTrue(keys.contains("abc123#watch"))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the whole track wait wakes on the round completion signal`() = runBlocking {
        val inFlight = mutableSetOf("abc123")
        var waitedMs = -1L
        val waits = KoAnalysisWaits(
            cache = { cacheStub },
            hasCompleteNonAltRendition = { false },
            awaitAnalysisHead = { _, _ -> true },
            awaitAnalysisRound = { videoId, timeoutMs ->
                waitedMs = timeoutMs
                inFlight.remove(videoId)
            },
            alignmentPrefixBytes = { 0L },
            renditionKeysFor = { emptyList() },
            refreshRenditionKeys = {},
            altSuffix = "#alt",
        )

        val result = waits.awaitAnalysisTrackFor("abc123", 5_000L)

        assertTrue(result)
        assertFalse("abc123" in inFlight)
        assertTrue("the round wait must be bounded and positive", waitedMs > 0L)
    }

    @Test
    fun `a failed head wait skips the round wait`() = runBlocking {
        var roundWaits = 0
        val waits = KoAnalysisWaits(
            cache = { cacheStub },
            hasCompleteNonAltRendition = { false },
            awaitAnalysisHead = { _, _ -> false },
            awaitAnalysisRound = { _, _ -> roundWaits++ },
            alignmentPrefixBytes = { 0L },
            renditionKeysFor = { emptyList() },
            refreshRenditionKeys = {},
            altSuffix = "#alt",
        )

        val result = waits.awaitAnalysisTrackFor("abc123", 5_000L)

        assertFalse(result)
        assertEquals(0, roundWaits)
    }
}
