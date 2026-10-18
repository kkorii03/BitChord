package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The read-ahead byte ceiling follows the connection: the whole-track Wi-Fi
 * ceiling is unchanged, a metered connection gets half of it, and an unknown
 * connection keeps the larger one rather than short-buffering the first play
 * of every launch.
 */
class PlaybackBufferCeilingTest {

    @Test
    fun `wifi keeps the whole-track ceiling`() {
        assertEquals(8 * 1024 * 1024, bufferCeilingBytes(metered = false))
        assertEquals(FAR_BUFFER_BYTES, bufferCeilingBytes(metered = false))
    }

    @Test
    fun `cellular is capped at four megabytes`() {
        assertEquals(4 * 1024 * 1024, bufferCeilingBytes(metered = true))
        assertEquals(CELLULAR_BUFFER_BYTES, bufferCeilingBytes(metered = true))
    }

    @Test
    fun `unknown connection keeps the wifi ceiling`() {
        assertEquals(FAR_BUFFER_BYTES, bufferCeilingBytes(metered = null))
    }
}
