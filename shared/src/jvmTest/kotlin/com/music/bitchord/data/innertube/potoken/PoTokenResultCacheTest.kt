package com.music.bitchord.data.innertube.potoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoTokenResultCacheTest {

    private class FakeStore : PoTokenStore {
        var record: String? = null
        var writes = 0
        var failNextWrite = false

        override fun read(): String? = record

        override fun write(record: String) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("write interrupted")
            }
            writes++
            this.record = record
        }
    }

    @Test
    fun `a recent token is reused only for the same visitor and video`() {
        val store = FakeStore()
        var now = 1_000L
        val cache = PoTokenResultCache(store) { now }
        val token = PoTokenResult(playerRequestPoToken = "request", streamingDataPoToken = "stream")

        assertNull(cache.get("visitor", "video"))
        cache.put("visitor", "video", token)

        assertEquals("request", cache.get("visitor", "video")?.playerRequestPoToken)
        assertEquals("stream", cache.get("visitor", "video")?.streamingDataPoToken)
        assertNull(cache.get("other-visitor", "video"))
        assertNull(cache.get("visitor", "other-video"))
    }

    @Test
    fun `an expired token is not reused`() {
        val store = FakeStore()
        var now = 1_000L
        val cache = PoTokenResultCache(store) { now }
        cache.put("visitor", "video", PoTokenResult("request", "stream"))

        now += PoTokenResultCache.TTL_MS + 1
        assertNull(cache.get("visitor", "video"))
    }

    @Test
    fun `a minted pair is persisted in one atomic write`() {
        val store = FakeStore()
        val cache = PoTokenResultCache(store) { 1_000L }

        cache.put("visitor-a", "video-a", PoTokenResult("request-a", "stream-a"))

        assertEquals("one editor call, or a process kill can split the pair", 1, store.writes)
    }

    @Test
    fun `an interrupted write never pairs one video's tokens with another`() {
        val store = FakeStore()
        val cache = PoTokenResultCache(store) { 1_000L }
        cache.put("visitor", "video-a", PoTokenResult("request-a", "stream-a"))

        store.failNextWrite = true
        runCatching { cache.put("visitor", "video-b", PoTokenResult("request-b", "stream-b")) }

        assertNull("video-b must not borrow video-a's tokens", cache.get("visitor", "video-b"))
        assertEquals("request-a", cache.get("visitor", "video-a")?.playerRequestPoToken)
    }
}
