package com.music.bitchord.data.sources

import com.music.bitchord.data.lyrics.ChromaAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reference profile cache used by HQ verification: bounded, access-ordered,
 * and only complete-enough profiles are eligible.
 */
class HqReferenceCacheTest {

    @Test
    fun `keeps the most recently used entries and evicts the oldest`() {
        val cache = HqReferenceCache(limit = 2)
        cache.put("a", features(frames = 100))
        cache.put("b", features(frames = 100))

        // Touching "a" makes "b" the oldest.
        assertNotNull(cache.get("a"))
        cache.put("c", features(frames = 100))

        assertEquals(2, cache.size())
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `clear drops every profile`() {
        val cache = HqReferenceCache(limit = 4)
        cache.put("a", features(frames = 100))
        cache.put("b", features(frames = 100))
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
    }

    @Test
    fun `only a profile covering most of the comparison window is kept`() {
        val full = features(frames = 100, hopSeconds = 1.0)
        val partial = features(frames = 10, hopSeconds = 1.0)

        assertTrue(HqReferenceCache.completeEnough(full, seconds = 90.0))
        assertTrue(!HqReferenceCache.completeEnough(partial, seconds = 90.0))
        assertTrue(!HqReferenceCache.completeEnough(full, seconds = 0.0))
    }

    @Test
    fun `a comparison needs both profiles to cover the window`() {
        val full = features(frames = 100, hopSeconds = 1.0)
        val partial = features(frames = 10, hopSeconds = 1.0)

        assertTrue(HqReferenceCache.bothCompleteEnough(full, full, seconds = 90.0))
        assertTrue(!HqReferenceCache.bothCompleteEnough(partial, full, seconds = 90.0))
        assertTrue(!HqReferenceCache.bothCompleteEnough(full, partial, seconds = 90.0))
        assertTrue(!HqReferenceCache.bothCompleteEnough(full, full, seconds = 0.0))
    }

    @Test
    fun `a read that reached the end is complete even when the track is shorter than the window`() {
        val short = features(frames = 60, hopSeconds = 1.0)

        assertTrue(HqReferenceCache.completeEnough(short, seconds = 90.0, reachedEnd = true))
        assertTrue(!HqReferenceCache.completeEnough(short, seconds = 90.0, reachedEnd = false))
        assertTrue(
            HqReferenceCache.bothCompleteEnough(
                short,
                short,
                seconds = 90.0,
                referenceReachedEnd = true,
                candidateReachedEnd = true,
            ),
        )
        assertTrue(
            !HqReferenceCache.bothCompleteEnough(
                short,
                short,
                seconds = 90.0,
                referenceReachedEnd = true,
                candidateReachedEnd = false,
            ),
        )
    }

    private fun features(frames: Int, hopSeconds: Double = 1.0): ChromaAlignment.Features =
        ChromaAlignment.Features(
            chroma = FloatArray(frames * 12),
            frames = frames,
            hopSeconds = hopSeconds,
            firstCenterSeconds = 0.0,
            silent = BooleanArray(frames),
        )
}
