package com.music.bitchord.data.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamFormat.isSuspectLossless] flags a lossless codec that states a bitrate
 * too low to really be lossless. The threshold is a strict `<`, so the value at
 * the threshold itself is not suspect.
 */
class StreamFormatSuspectLosslessTest {

    @Test
    fun `a lossless codec below the threshold is suspect`() {
        assertTrue(StreamFormat(codec = "flac", kbps = 299).isSuspectLossless)
        assertTrue(StreamFormat(codec = "alac", kbps = 128).isSuspectLossless)
    }

    @Test
    fun `the threshold itself is not suspect`() {
        assertFalse(StreamFormat(codec = "flac", kbps = 300).isSuspectLossless)
        assertFalse(StreamFormat(codec = "flac", kbps = 1411).isSuspectLossless)
    }

    @Test
    fun `a low bitrate on a lossy codec is never suspect lossless`() {
        assertFalse(StreamFormat(codec = "opus", kbps = 128).isSuspectLossless)
        assertFalse(StreamFormat(codec = "aac", kbps = 96).isSuspectLossless)
    }

    @Test
    fun `an unstated bitrate or unknown codec cannot be judged`() {
        assertFalse(StreamFormat(codec = "flac").isSuspectLossless)
        assertFalse(StreamFormat(codec = null, kbps = 128).isSuspectLossless)
    }
}
