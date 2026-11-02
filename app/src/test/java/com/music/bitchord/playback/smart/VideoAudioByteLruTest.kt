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
    fun replacingAKeyAdjustsTheTotal() {
        val cache = cache(100)
        cache.put("a", Clip(ByteArray(80)))
        cache.put("a", Clip(ByteArray(20)))
        assertEquals(20, cache.sizeBytes())
        assertEquals(1, cache.size())
    }
}
