package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** The song cache keeps a ceiling, so a long session cannot grow without bound. */
class SongCacheEvictionCountTest {

    @Test
    fun `a cache below the ceiling evicts nothing`() {
        assertEquals(0, songCacheEvictionCount(size = 10, max = 20))
    }

    @Test
    fun `a full cache evicts one to make room for the next song`() {
        assertEquals(1, songCacheEvictionCount(size = 20, max = 20))
    }

    @Test
    fun `an oversized cache evicts the overflow plus the new entry`() {
        assertEquals(5, songCacheEvictionCount(size = 24, max = 20))
    }
}
