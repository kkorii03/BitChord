package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SilenceSkipperTest {

    private val sampleRate = 8_000
    private val channels = 2

    @Test
    fun `switched off leaves the block untouched`() {
        val skipper = SilenceSkipper().apply { configure(sampleRate, channels) }
        val block = blockOf(silence(4_000))

        skipper.process(block)

        assertEquals(4_000, block.frameCount)
        assertEquals(0L, skipper.skippedFrames)
    }

    @Test
    fun `samples at the threshold count as silence`() {
        val skipper = skipper(minSilenceUs = 250_000L, keptEachEndUs = 10_000L)
        val block = blockOf(
            silence(3_000, SilenceSkipper.SILENCE_THRESHOLD),
            tone(500),
        )

        skipper.process(block)

        assertTrue("a threshold-level run should be skipped", skipper.skippedFrames > 0L)
        assertTrue(block.frameCount < 3_500)
    }

    @Test
    fun `samples above the threshold keep the run audible`() {
        val skipper = skipper(minSilenceUs = 250_000L, keptEachEndUs = 10_000L)
        val block = blockOf(
            silence(3_000, SilenceSkipper.SILENCE_THRESHOLD * 1.01f),
            tone(500),
        )

        skipper.process(block)

        assertEquals(0L, skipper.skippedFrames)
        assertEquals(3_500, block.frameCount)
    }

    @Test
    fun `a rest shorter than the minimum is left alone`() {
        val skipper = skipper(minSilenceUs = 250_000L, keptEachEndUs = 10_000L)
        val block = blockOf(tone(500), silence(1_600), tone(500))

        skipper.process(block)

        assertEquals(0L, skipper.skippedFrames)
        assertEquals(2_600, block.frameCount)
    }

    @Test
    fun `a run longer than the minimum is cut`() {
        val skipper = skipper(minSilenceUs = 250_000L, keptEachEndUs = 10_000L)
        val block = blockOf(tone(500), silence(2_400), tone(500))

        skipper.process(block)

        assertTrue("a long rest should be cut", skipper.skippedFrames > 0L)
        assertTrue(block.frameCount < 3_400)
    }

    @Test
    fun `a run straddling a block boundary is still detected`() {
        val skipper = skipper(minSilenceUs = 300_000L, keptEachEndUs = 100_000L)
        val first = blockOf(tone(800), silence(1_600))
        val second = blockOf(silence(1_600), tone(800))

        skipper.process(first)
        assertEquals("the first block is below the minimum", 0L, skipper.skippedFrames)
        assertEquals(2_400, first.frameCount)

        skipper.process(second)

        // Threshold is crossed 800 frames into the second block: those frames
        // are dropped, the last 800 of the run are replayed, then the tone.
        assertEquals(800L, skipper.skippedFrames)
        assertEquals(1_600, second.frameCount)
        // The replayed tail starts 800 frames into the block; the media clock
        // has to start there rather than at the block's silent first frame.
        assertEquals(800, skipper.firstOutputFrameOffset)
    }

    @Test
    fun `a tail carried into a music-first block cannot overwrite its input`() {
        val skipper = skipper(minSilenceUs = 250_000L, keptEachEndUs = 100_000L)
        val first = blockOf(tone(500), silence(3_000))
        skipper.process(first)
        assertTrue("the first block should end inside a skipped run", skipper.skippedFrames > 0L)

        val music = tone(500)
        val second = blockOf(music)
        skipper.process(second)

        assertEquals(500, second.frameCount)
        for (index in music.indices) {
            assertEquals("input corrupted at $index", music[index], second.samples[index], 0f)
        }
    }

    @Test
    fun `music either side of a cut is preserved sample for sample`() {
        val skipper = skipper(minSilenceUs = 250_000L, keptEachEndUs = 10_000L)
        val leading = tone(800)
        val trailing = tone(800, startFrame = 800 + 4_000)
        val block = blockOf(leading, silence(4_000), trailing)

        skipper.process(block)

        val outputFrames = block.frameCount
        val output = FloatArray(outputFrames * channels)
        System.arraycopy(block.samples, 0, output, 0, output.size)

        val leadingSamples = leading.size
        for (index in leading.indices) {
            assertEquals("leading music altered at $index", leading[index], output[index], 0f)
        }
        val trailingOffset = output.size - trailing.size
        for (index in trailing.indices) {
            assertEquals(
                "trailing music altered at $index",
                trailing[index],
                output[trailingOffset + index],
                0f,
            )
        }
        assertTrue(trailingOffset > leadingSamples)
    }

    private fun skipper(minSilenceUs: Long, keptEachEndUs: Long): SilenceSkipper =
        SilenceSkipper(minSilenceUs = minSilenceUs, keptEachEndUs = keptEachEndUs).apply {
            enabled = true
            configure(sampleRate, channels)
        }

    private fun blockOf(vararg parts: FloatArray): AudioBlock {
        var frames = 0
        for (part in parts) frames += part.size / channels
        val block = AudioBlock(channelCount = channels, capacityFrames = frames)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, block.samples, offset, part.size)
            offset += part.size
        }
        block.setFrameCount(frames)
        return block
    }

    private fun tone(frames: Int, startFrame: Int = 0): FloatArray =
        FloatArray(frames * channels) { index ->
            if (index % channels == 0) {
                (0.5 * sin(2.0 * PI * 300.0 * (startFrame + index / channels) / sampleRate)).toFloat()
            } else {
                -0.25f
            }
        }

    private fun silence(frames: Int, amplitude: Float = 0f): FloatArray =
        FloatArray(frames * channels) { amplitude }
}
