package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 11-02 heap report: VideoAudio.recent and recentSongs kept every decoded
 * clip, 11 MB by the end of a session. The two caches now share one total
 * byte budget and evict the oldest value.
 */
class VideoAudioByteLruTest {

    private data class Clip(val bytes: ByteArray)

    private fun cache(maxBytes: Int) = ByteLru<String, Clip>(maxBytes) { it.bytes.size }

    @Test
    fun theTotalStaysUnderTheBudgetAndEvictsTheOldest() {
        val cache = cache(100)
        cache.put("a", Clip(ByteArray(50)))
        cache.put("b", Clip(ByteArray(50)))
        assertEquals(100, cache.sizeBytes())

        // "a" just played, so "b" is the oldest and goes for "c".
        cache.get("a")
        cache.put("c", Clip(ByteArray(50)))

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(100, cache.sizeBytes())
    }

    @Test
    fun aClipLargerThanTheBudgetIsReturnedByTheCallerButNotKept() {
        val cache = cache(100)
        cache.put("long", Clip(ByteArray(150)))
        assertNull(cache.get("long"))
        assertEquals(0, cache.sizeBytes())
    }

    @Test
    fun aClipLargerThanTheBudgetDoesNotFlushWhatIsCached() {
        // Review r1: the oversized put evicted everything, then itself.
        val cache = cache(100)
        cache.put("a", Clip(ByteArray(40)))
        cache.put("b", Clip(ByteArray(40)))
        cache.put("long", Clip(ByteArray(150)))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertEquals(80, cache.sizeBytes())
    }

    @Test
    fun replacingAKeyAdjustsTheTotal() {
        val cache = cache(100)
        cache.put("a", Clip(ByteArray(80)))
        cache.put("a", Clip(ByteArray(20)))
        assertEquals(20, cache.sizeBytes())
        assertEquals(1, cache.size())
    }

    /**
     * The playback caches bound their entry count by giving every value a size
     * of one, so the same class keeps a long session from growing: the oldest
     * entry goes, not the whole map.
     */
    @Test
    fun anEntryCountBoundEvictsTheOldestAndKeepsTheRest() {
        val cache = ByteLru<String, Int>(3) { 1 }
        listOf("a", "b", "c").forEach { cache.put(it, 1) }
        cache.get("a")
        cache.put("d", 1)

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertEquals(3, cache.size())
        assertEquals(3, cache.sizeBytes())
    }

    @Test
    fun clearDropsEverythingAndTheTotal() {
        val cache = cache(100)
        cache.put("a", Clip(ByteArray(40)))
        cache.clear()

        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
        assertEquals(0, cache.sizeBytes())
        // A cleared cache still takes new entries.
        cache.put("b", Clip(ByteArray(40)))
        assertEquals(40, cache.sizeBytes())
    }
}
