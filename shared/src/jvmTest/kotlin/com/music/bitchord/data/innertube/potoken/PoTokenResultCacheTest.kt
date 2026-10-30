package com.music.bitchord.data.innertube.potoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoTokenResultCacheTest {

    private class FakeStore : PoTokenStore {
        val strings = HashMap<String, String>()
        val longs = HashMap<String, Long>()

        override fun getString(key: String): String? = strings[key]

        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun getLong(key: String): Long? = longs[key]

        override fun putLong(key: String, value: Long) {
            longs[key] = value
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
}
