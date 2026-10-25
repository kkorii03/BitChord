package com.music.bitchord.playback.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A linear resampler in Media3's audio-processor chain.
 *
 * The reported failure was an 88200 Hz PCM16 AudioTrack on a Bluetooth route
 * that opened successfully and then produced no sound. Opening the same audio
 * at 48000 Hz worked. This processor is the fallback for exactly that case:
 * when [targetSampleRateHz] names a different rate, it changes the format the
 * delegate opens and resamples every buffer into it.
 *
 * It is deliberately placed *before* Media3's silence skipper and Sonic stage,
 * so both of those see the rate AudioTrack will actually run at. When the
 * provider says "keep the source rate" it copies the buffer through untouched,
 * which is the path every ordinary 44.1/48 kHz track takes.
 *
 * Linear interpolation is not a mastering-grade sample-rate converter, but
 * this path only engages after the platform route has already failed to carry
 * the native rate; the alternative on those routes is silence.
 */
@UnstableApi
class ResamplingAudioProcessor(
    private val targetSampleRateHz: (inputRateHz: Int) -> Int?,
) : BaseAudioProcessor() {

    private var inputRateHz = 0
    private var outputRateHz = 0
    private var channelCount = 0
    private var inputEncoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var resampling = false

    /** Last input frame of the previous block, so the join is interpolated, not stepped. */
    private var previousFrame = FloatArray(0)

    /** Source-frame offset carried into the next block, in units of current-block frames. */
    private var phase = 0.0

    /** True until the first block has seeded [previousFrame]. */
    private var firstBlock = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        inputEncoding = inputAudioFormat.encoding
        bytesPerSample = when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        val target = runCatching { targetSampleRateHz(inputRateHz) }.getOrNull()
        val canResample = bytesPerSample > 0 &&
            channelCount > 0 &&
            inputRateHz > 0 &&
            target != null &&
            target > 0 &&
            target != inputRateHz

        if (!canResample) {
            resampling = false
            outputRateHz = inputRateHz
            previousFrame = FloatArray(0)
            return inputAudioFormat
        }

        outputRateHz = target ?: inputRateHz
        resampling = true
        if (previousFrame.size != channelCount) previousFrame = FloatArray(channelCount)
        phase = 0.0
        firstBlock = true
        Log.d(
            TAG,
            "resampling $inputRateHz -> $outputRateHz Hz ch=$channelCount " +
                "encoding=${if (inputEncoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"}",
        )
        TrackLog.d(
            "AUDIO_OUT",
            "resample $inputRateHz -> $outputRateHz Hz ch=$channelCount " +
                "encoding=${if (inputEncoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"}",
        )
        return AudioProcessor.AudioFormat(outputRateHz, channelCount, inputEncoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (inputBuffer.remaining() <= 0) return
        if (!resampling) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val bytesPerFrame = bytesPerSample * channelCount
        val frames = inputBuffer.remaining() / bytesPerFrame
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (firstBlock) {
            for (channel in 0 until channelCount) {
                previousFrame[channel] = readSample(inputBuffer, 0, channel)
            }
            phase = 0.0
            firstBlock = false
        }

        val ratio = inputRateHz.toDouble() / outputRateHz
        val maxOutputFrames = (ceil((frames + phase) / ratio).toInt() + 2).coerceAtLeast(1)
        val output = replaceOutputBuffer(maxOutputFrames * bytesPerFrame)
        output.order(ByteOrder.LITTLE_ENDIAN)

        var position = phase
        val lastSourcePosition = frames.toDouble()
        while (position <= lastSourcePosition + POSITION_EPSILON && output.remaining() >= bytesPerFrame) {
            val base = floor(position).toInt()
            val fraction = (position - base).toFloat()
            for (channel in 0 until channelCount) {
                val first = if (base == 0) {
                    previousFrame[channel]
                } else {
                    readSample(inputBuffer, base - 1, channel)
                }
                val second = if (base < frames) {
                    readSample(inputBuffer, base, channel)
                } else {
                    first
                }
                writeSample(output, first + (second - first) * fraction)
            }
            position += ratio
        }

        for (channel in 0 until channelCount) {
            previousFrame[channel] = readSample(inputBuffer, frames - 1, channel)
        }
        phase = (position - frames).coerceAtLeast(0.0)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        phase = 0.0
        firstBlock = true
        previousFrame.fill(0f)
    }

    override fun onReset() {
        inputRateHz = 0
        outputRateHz = 0
        channelCount = 0
        inputEncoding = C.ENCODING_INVALID
        bytesPerSample = 0
        resampling = false
        previousFrame = FloatArray(0)
        phase = 0.0
        firstBlock = true
    }

    private fun readSample(buffer: ByteBuffer, frame: Int, channel: Int): Float {
        val index = frame * channelCount * bytesPerSample + channel * bytesPerSample
        return when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> buffer.getShort(index).toFloat() / 32768.0f
            else -> PcmBoundary.sanitizeFloat(buffer.getFloat(index))
        }
    }

    private fun writeSample(buffer: ByteBuffer, sample: Float) {
        when (inputEncoding) {
            C.ENCODING_PCM_16BIT -> buffer.putShort(PcmBoundary.clamp16FromFloat(sample))
            else -> buffer.putFloat(PcmBoundary.sanitizeFloat(sample))
        }
    }

    private companion object {
        const val TAG = "BitChordResample"
        const val POSITION_EPSILON = 1e-9
    }
}
