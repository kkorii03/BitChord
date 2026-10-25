package com.music.bitchord.data.sources

import com.music.bitchord.data.BoundedMemo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bounded memo behind the repeated title/artist normalisation in
 * [TrackMatcher]: it must compute once per key, stay bounded, and evict the
 * least recently used answer rather than the newest.
 */
class BoundedMemoTest {

    @Test
    fun `computes a key once and reuses the answer`() {
        val memo = BoundedMemo<String, String>(limit = 4)
        var computed = 0

        val first = memo.getOrPut("a") { computed++; "A" }
        val second = memo.getOrPut("a") { computed++; "A2" }

        assertEquals("A", first)
        assertEquals("A", second)
        assertEquals(1, computed)
        assertEquals(1, memo.size())
    }

    @Test
    fun `evicts the least recently used entry past the limit`() {
        val memo = BoundedMemo<String, Int>(limit = 2)
        memo.getOrPut("a") { 1 }
        memo.getOrPut("b") { 2 }
        // Touch "a" so "b" becomes the oldest.
        assertEquals(1, memo.get("a"))

        memo.getOrPut("c") { 3 }

        assertEquals(2, memo.size())
        assertEquals(1, memo.get("a"))
        assertNull(memo.get("b"))
        assertEquals(3, memo.get("c"))
    }

    @Test
    fun `clear drops every answer`() {
        val memo = BoundedMemo<String, Int>(limit = 4)
        memo.getOrPut("a") { 1 }
        memo.getOrPut("b") { 2 }

        memo.clear()

        assertEquals(0, memo.size())
        assertNull(memo.get("a"))
    }

    @Test
    fun `a failed calculation is not cached`() {
        val memo = BoundedMemo<String, Int>(limit = 4)
        var attempts = 0

        repeat(2) {
            runCatching { memo.getOrPut("a") { attempts++; error("no") } }
        }

        assertEquals(2, attempts)
        assertEquals(0, memo.size())
    }
}
