package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoCrossfadeLyricCacheTest {

    @Test
    fun `a track is asked only once`() {
        val cache = KoCrossfadeLyricCache<String>()
        assertTrue(cache.markAsked("a"))
        assertFalse(cache.markAsked("a"))
    }

    @Test
    fun `the cache never grows past its bound`() {
        val cache = KoCrossfadeLyricCache<String>(maxEntries = 4)
        repeat(20) { cache.put("track$it", "spans") }
        assertEquals(4, cache.size())
    }

    @Test
    fun `an evicted track can be asked again`() {
        val cache = KoCrossfadeLyricCache<String>(maxEntries = 2)
        cache.put("a", "spans")
        cache.put("b", "spans")
        cache.put("c", "spans")
        assertNull(cache["a"])
        assertTrue(cache.markAsked("a"))
    }

    @Test
    fun `recently read entries survive the bound`() {
        val cache = KoCrossfadeLyricCache<String>(maxEntries = 2)
        cache.put("a", "spans")
        cache.put("b", "spans")
        assertEquals("spans", cache["a"])
        cache.put("c", "spans")
        assertEquals("spans", cache["a"])
        assertNull(cache["b"])
    }
}
