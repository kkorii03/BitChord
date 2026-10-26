package com.music.bitchord.data.lyrics

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WavAudioTest {

    @Test
    fun `16 bit mono samples become floats`() {
        val pcm = assertNotNull(
            WavAudio.monoFloat(
                wav(
                    sampleRate = 16_000,
                    channels = 1,
                    samples = shortArrayOf(0, 16384, -16384, 32767, -32768),
                ),
            ),
        )
        assertEquals(16_000, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(5, pcm.samples.size)
        assertSample(0f, pcm.samples[0])
        assertSample(0.5f, pcm.samples[1])
        assertSample(-0.5f, pcm.samples[2])
        assertSample(32767f / 32768f, pcm.samples[3])
        assertSample(-1f, pcm.samples[4])
    }

    @Test
    fun `stereo frames are averaged to mono`() {
        val pcm = assertNotNull(
            WavAudio.monoFloat(
                wav(
                    sampleRate = 44_100,
                    channels = 2,
                    samples = shortArrayOf(
                        16384, -16384,
                        32767, 32767,
                        0, 16384,
                    ),
                ),
            ),
        )
        assertEquals(44_100, pcm.sampleRate)
        assertEquals(2, pcm.channels)
        assertEquals(3, pcm.samples.size)
        assertSample(0f, pcm.samples[0])
        assertSample(32767f / 32768f, pcm.samples[1])
        assertSample(0.25f, pcm.samples[2])
    }

    @Test
    fun `the header's sample rate is carried through, not assumed`() {
        val pcm = assertNotNull(
            WavAudio.monoFloat(
                wav(
                    sampleRate = 8_000,
                    channels = 1,
                    samples = shortArrayOf(1000, -1000),
                ),
            ),
        )
        assertEquals(8_000, pcm.sampleRate)
        assertEquals(2, pcm.samples.size)
    }

    @Test
    fun `chunks before the samples are skipped`() {
        val pcm = assertNotNull(
            WavAudio.monoFloat(
                wav(
                    sampleRate = 16_000,
                    channels = 1,
                    samples = shortArrayOf(8192),
                    extraChunk = true,
                ),
            ),
        )
        assertEquals(1, pcm.samples.size)
        assertSample(0.25f, pcm.samples[0])
    }

    @Test
    fun `anything but 16 bit pcm is refused`() {
        assertNull(WavAudio.monoFloat(ByteArray(0)))
        assertNull(WavAudio.monoFloat("not a wav".toByteArray()))
        assertNull(
            WavAudio.monoFloat(
                wav(
                    sampleRate = 16_000,
                    channels = 1,
                    samples = shortArrayOf(1),
                    bits = 8,
                ),
            ),
        )
    }

    private fun assertSample(expected: Float, actual: Float) {
        assertEquals(expected, actual, 1e-6f)
    }

    private fun wav(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        bits: Int = 16,
        extraChunk: Boolean = false,
    ): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(data::putShort)
        val dataBytes = data.array()
        val extra = if (extraChunk) 8 + 3 + 1 else 0
        val total = 12 + extra + 8 + 16 + 8 + dataBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray())
        out.putInt(total - 8)
        out.put("WAVE".toByteArray())
        if (extraChunk) {
            out.put("LIST".toByteArray())
            out.putInt(3)
            out.put(byteArrayOf(1, 2, 3))
            out.put(0)
        }
        out.put("fmt ".toByteArray())
        out.putInt(16)
        out.putShort(1)
        out.putShort(channels.toShort())
        out.putInt(sampleRate)
        out.putInt(sampleRate * channels * (bits / 8))
        out.putShort((channels * (bits / 8)).toShort())
        out.putShort(bits.toShort())
        out.put("data".toByteArray())
        out.putInt(dataBytes.size)
        out.put(dataBytes)
        return out.array()
    }
}
