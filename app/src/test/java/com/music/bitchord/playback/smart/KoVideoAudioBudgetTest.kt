package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cold1119 (heap-0359 holder 5): the decoded-audio cache keeps what is really
 * reused (the playing song's and the next song's 90 s openings, plus one
 * abandoned partial) and no more. The old 16 MiB budget fails the upper bound.
 */
class KoVideoAudioBudgetTest {
    /** 16 kHz mono 16-bit, plus the 44-byte header: 2,880,044 bytes (the dump's clip size). */
    private val opening = 44 + 16_000 * 2 * 90

    @Test
    fun budgetHoldsThreeOpeningsAndIsSmallerThanBefore() {
        assertTrue(VideoAudio.MAX_CACHED_AUDIO_BYTES >= 3 * opening)
        assertTrue(VideoAudio.MAX_CACHED_AUDIO_BYTES < 16 * 1024 * 1024)
    }

    @Test
    fun currentNextAndPartialOpeningsStayTogether() {
        val lru = ByteLru<String, ByteArray>(VideoAudio.MAX_CACHED_AUDIO_BYTES) { it.size }
        lru.put("song\u0000current", ByteArray(opening))
        lru.put("song\u0000next", ByteArray(opening))
        lru.put("partial\u0000current", ByteArray(opening))
        assertEquals(3, lru.size())
        assertNotNull(lru.get("song\u0000current"))
    }

    @Test
    fun aSevenMinuteWholeClipIsNotCachedAndEvictsNothing() {
        val lru = ByteLru<String, ByteArray>(VideoAudio.MAX_CACHED_AUDIO_BYTES) { it.size }
        lru.put("song\u0000current", ByteArray(opening))
        lru.put("song\u0000next", ByteArray(opening))
        lru.put("video\u0000long", ByteArray(44 + 16_000 * 2 * 420))
        assertNull(lru.get("video\u0000long"))
        assertEquals(2, lru.size())
    }

    @Test
    fun aFourMinuteWholeClipStillFitsForAiTimingAndAiLyrics() {
        val lru = ByteLru<String, ByteArray>(VideoAudio.MAX_CACHED_AUDIO_BYTES) { it.size }
        lru.put("video\u0000song", ByteArray(44 + 16_000 * 2 * 240))
        assertNotNull(lru.get("video\u0000song"))
    }
}
