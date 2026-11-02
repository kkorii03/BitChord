package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 11-02 smoke heap report: 16 song-check/AI-timing requests waiting at
 * AudioDecoder's one-at-a-time gate held 90 MB of pre-sized buffers, and
 * playback then failed with OutOfMemoryError. A request that has not decoded
 * anything must hold no buffer.
 */
class VideoAudioResamplerTest {
    @Test
    fun aWaitingRequestHoldsNoBuffer() {
        // Allocated eagerly this is an OutOfMemoryError on construction.
        val waiting = VideoAudio.Resampler(Int.MAX_VALUE)
        assertEquals(0, waiting.bytes().size)
    }

    @Test
    fun decodedChunksStillBecomeSixteenBitMonoAtTheTargetRate() {
        val resampler = VideoAudio.Resampler(1024)
        resampler.push(FloatArray(32_000) { 0.5f }, 32_000)
        val pcm = resampler.bytes()
        // One second at 32 kHz in, one second at 16 kHz × 2 bytes out.
        assertEquals(32_000, pcm.size)
        val first = (pcm[0].toInt() and 0xff) or (pcm[1].toInt() shl 8)
        assertEquals((0.5 * Short.MAX_VALUE).toInt(), first)
    }
}
