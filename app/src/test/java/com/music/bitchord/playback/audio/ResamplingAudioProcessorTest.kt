package com.music.bitchord.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@androidx.annotation.OptIn(UnstableApi::class)
class ResamplingAudioProcessorTest {

    @Test
    fun `one kilohertz survives the 88k to 48k conversion`() {
        val input = sine(1_000.0)
        val output = run(input, input.size)

        val level = relativeLevelDb(output, AMPLITUDE)

        assertTrue("1 kHz level changed by $level dB", abs(level) <= 1.0)
    }

    @Test
    fun `thirty kilohertz is removed before it can alias`() {
        val input = sine(30_000.0)
        val output = run(input, input.size)

        val level = relativeLevelDb(output, AMPLITUDE)

        assertTrue("30 kHz leaked at $level dB", level <= -60.0)
    }

    @Test
    fun `block boundaries do not change the result`() {
        val input = sine(1_000.0)
        val whole = run(input, input.size)
        val split = run(input, 997)

        assertEquals("output frame count", whole.size, split.size)
        var largest = 0.0f
        for (index in whole.indices) {
            largest = max(largest, abs(whole[index] - split[index]))
        }
        assertTrue("block split changed the output by $largest", largest <= 1e-4f)
    }

    @Test
    fun `no target rate leaves the samples untouched`() {
        val input = sine(1_000.0).copyOf(4_096)
        val output = run(input, 1_024, targetRate = null)

        assertEquals("passthrough frame count", input.size, output.size)
        for (index in input.indices) {
            assertEquals("sample $index", input[index], output[index], 0.0f)
        }
    }

    private fun run(
        input: FloatArray,
        chunkFrames: Int,
        targetRate: Int? = TARGET_RATE,
    ): FloatArray {
        val processor = ResamplingAudioProcessor { targetRate }
        processor.configure(AudioProcessor.AudioFormat(SOURCE_RATE, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()

        val output = ArrayList<Float>(input.size)
        var offset = 0
        while (offset < input.size) {
            val length = minOf(chunkFrames, input.size - offset)
            val buffer = ByteBuffer.allocateDirect(length * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until length) buffer.putFloat(input[offset + index])
            buffer.flip()
            processor.queueInput(buffer)

            val produced = processor.output
            val frames = produced.remaining() / 4
            val chunk = FloatArray(frames)
            produced.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(chunk)
            for (sample in chunk) output.add(sample)
            offset += length
        }
        return output.toFloatArray()
    }

    /** RMS of the settled part of [output], relative to a sine of [amplitude] at full level. */
    private fun relativeLevelDb(output: FloatArray, amplitude: Double): Double {
        val settle = TARGET_RATE / 10
        require(output.size > settle * 2) { "not enough output to measure" }
        var sum = 0.0
        for (index in settle until output.size - settle) {
            sum += output[index] * output[index]
        }
        val rms = sqrt(sum / (output.size - 2 * settle))
        return 20.0 * log10(rms / (amplitude / sqrt(2.0)))
    }

    private fun sine(hz: Double): FloatArray {
        val frames = (SOURCE_RATE * SECONDS).toInt()
        return FloatArray(frames) { frame ->
            (AMPLITUDE * sin(2.0 * PI * hz * frame / SOURCE_RATE)).toFloat()
        }
    }

    private companion object {
        const val SOURCE_RATE = 88_200
        const val TARGET_RATE = 48_000
        const val SECONDS = 1.0
        const val AMPLITUDE = 0.5
    }
}
