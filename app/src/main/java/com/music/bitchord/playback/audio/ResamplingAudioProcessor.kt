package com.music.bitchord.playback.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.TrackLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A band-limited resampler in Media3's audio-processor chain.
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
 * which is the path every ordinary 44.1/48 kHz track takes. The resampling
 * path itself only engages for the 88.2 kHz family on a route that did not
 * carry it natively; see [com.music.bitchord.playback.AudioOutputPolicy].
 *
 * Downsampling is preceded by a windowed-sinc FIR low-pass whose corner is
 * 0.45 of the output rate and whose stopband starts at the output Nyquist.
 * Without it the linear interpolator folded everything above the new Nyquist
 * back into the audible band. The linear stage then fills the fractional
 * positions between filtered samples. It is not a mastering-grade converter,
 * but this path only engages after the platform route has already failed to
 * carry the native rate; the alternative on those routes is silence.
 *
 * The filter history is per-channel and carried across blocks, so a block
 * boundary is not a discontinuity. Every buffer is allocated in [onConfigure];
 * the audio thread only reads and writes them.
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
    private var filtering = false

    /** Last input frame of the previous block, already filtered when [filtering]. */
    private var previousFrame = FloatArray(0)

    /** Source-frame offset carried into the next block, in units of current-block frames. */
    private var phase = 0.0

    /** True until the first block has seeded [previousFrame] and the filter history. */
    private var firstBlock = true

    /** Windowed-sinc low-pass coefficients, empty when the output rate is not lower. */
    private var filterCoefficients = FloatArray(0)
    private var filterTaps = 0

    /** Per-channel ring of the last [filterTaps] raw input frames. */
    private var filterHistory = FloatArray(0)
    private var filterHistoryPos = 0

    /** Last current-block frame written into [filterHistory], or -1 before the first. */
    private var filterProcessedFrame = -1

    /** One filtered frame cached for the next output position; see [filteredFrameInto]. */
    private var filterCacheIndex = -1
    private var filterCache = FloatArray(0)

    /** Scratch for the two integer frames an output position interpolates between. */
    private var filterFirst = FloatArray(0)
    private var filterSecond = FloatArray(0)

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
            filtering = false
            outputRateHz = inputRateHz
            previousFrame = FloatArray(0)
            filterFirst = FloatArray(0)
            filterSecond = FloatArray(0)
            releaseFilter()
            return inputAudioFormat
        }

        outputRateHz = target ?: inputRateHz
        resampling = true
        if (previousFrame.size != channelCount) previousFrame = FloatArray(channelCount)
        if (filterFirst.size != channelCount) {
            filterFirst = FloatArray(channelCount)
            filterSecond = FloatArray(channelCount)
        }
        phase = 0.0
        firstBlock = true
        filtering = outputRateHz < inputRateHz
        if (filtering) {
            val coefficients = designLowPass(inputRateHz, outputRateHz)
            if (coefficients != null && coefficients.isNotEmpty()) {
                filterCoefficients = coefficients
                filterTaps = coefficients.size
                filterHistory = FloatArray(channelCount * filterTaps)
                filterHistoryPos = 0
                filterProcessedFrame = -1
                filterCacheIndex = -1
                filterCache = FloatArray(channelCount)
            } else {
                filtering = false
                releaseFilter()
            }
        } else {
            releaseFilter()
        }
        Log.d(
            TAG,
            "resampling $inputRateHz -> $outputRateHz Hz ch=$channelCount " +
                "encoding=${if (inputEncoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"} " +
                "filter=${if (filtering) "$filterTaps taps" else "off"}",
        )
        TrackLog.d(
            "AUDIO_OUT",
            "resample $inputRateHz -> $outputRateHz Hz ch=$channelCount " +
                "encoding=${if (inputEncoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"} " +
                "filter=${if (filtering) "$filterTaps taps" else "off"}",
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
            seedFirstBlock(inputBuffer)
            phase = 0.0
            firstBlock = false
        }
        filterProcessedFrame = -1
        filterCacheIndex = -1

        val ratio = inputRateHz.toDouble() / outputRateHz
        val maxOutputFrames = (ceil((frames + phase) / ratio).toInt() + 2).coerceAtLeast(1)
        val output = replaceOutputBuffer(maxOutputFrames * bytesPerFrame)
        output.order(ByteOrder.LITTLE_ENDIAN)

        var position = phase
        val lastSourcePosition = frames.toDouble()
        while (position <= lastSourcePosition + POSITION_EPSILON && output.remaining() >= bytesPerFrame) {
            val base = floor(position).toInt()
            val fraction = (position - base).toFloat()
            if (base == 0) {
                previousFrame.copyInto(filterFirst)
            } else {
                filteredFrameInto(inputBuffer, base - 1, filterFirst)
            }
            if (base < frames) {
                filteredFrameInto(inputBuffer, base, filterSecond)
            } else {
                filterFirst.copyInto(filterSecond)
            }
            for (channel in 0 until channelCount) {
                val first = filterFirst[channel]
                val second = filterSecond[channel]
                writeSample(output, first + (second - first) * fraction)
            }
            position += ratio
        }

        filteredFrameInto(inputBuffer, frames - 1, previousFrame)
        phase = (position - frames).coerceAtLeast(0.0)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        phase = 0.0
        firstBlock = true
        previousFrame.fill(0f)
        filterHistory.fill(0f)
        filterHistoryPos = 0
        filterProcessedFrame = -1
        filterCacheIndex = -1
        filterCache.fill(0f)
        filterFirst.fill(0f)
        filterSecond.fill(0f)
    }

    override fun onReset() {
        inputRateHz = 0
        outputRateHz = 0
        channelCount = 0
        inputEncoding = C.ENCODING_INVALID
        bytesPerSample = 0
        resampling = false
        filtering = false
        previousFrame = FloatArray(0)
        phase = 0.0
        firstBlock = true
        releaseFilter()
        filterFirst = FloatArray(0)
        filterSecond = FloatArray(0)
    }

    /**
     * Seeds the interpolator and the FIR history from the first frame.
     *
     * Repeating that frame through the history makes the filter's DC gain
     * produce the same first sample the old linear path did, instead of a
     * fade-in from zero. It also means the first block has no special case in
     * the sample loop.
     */
    private fun seedFirstBlock(inputBuffer: ByteBuffer) {
        if (filtering) {
            for (channel in 0 until channelCount) {
                val first = readSample(inputBuffer, 0, channel)
                val offset = channel * filterTaps
                for (tap in 0 until filterTaps) filterHistory[offset + tap] = first
                previousFrame[channel] = first
            }
            filterHistoryPos = 0
        } else {
            for (channel in 0 until channelCount) {
                previousFrame[channel] = readSample(inputBuffer, 0, channel)
            }
        }
    }

    /**
     * Fills [target] with the frame at [frame] in the current block, after the
     * anti-alias filter when one is active. The one-entry cache matters for
     * the 88.2 -> 48 kHz ratio: consecutive output positions usually share an
     * integer sample.
     */
    private fun filteredFrameInto(inputBuffer: ByteBuffer, frame: Int, target: FloatArray) {
        if (!filtering) {
            for (channel in 0 until channelCount) {
                target[channel] = readSample(inputBuffer, frame, channel)
            }
            return
        }
        if (frame == filterCacheIndex) {
            filterCache.copyInto(target)
            return
        }
        advanceFilterHistory(inputBuffer, frame)
        for (channel in 0 until channelCount) {
            filterCache[channel] = convolve(channel)
        }
        filterCacheIndex = frame
        filterCache.copyInto(target)
    }

    private fun advanceFilterHistory(inputBuffer: ByteBuffer, upToFrame: Int) {
        while (filterProcessedFrame < upToFrame) {
            filterProcessedFrame += 1
            for (channel in 0 until channelCount) {
                filterHistory[channel * filterTaps + filterHistoryPos] =
                    readSample(inputBuffer, filterProcessedFrame, channel)
            }
            filterHistoryPos = (filterHistoryPos + 1) % filterTaps
        }
    }

    private fun convolve(channel: Int): Float {
        val offset = channel * filterTaps
        var index = if (filterHistoryPos == 0) filterTaps - 1 else filterHistoryPos - 1
        var sum = 0.0f
        for (tap in 0 until filterTaps) {
            sum += filterCoefficients[tap] * filterHistory[offset + index]
            index -= 1
            if (index < 0) index = filterTaps - 1
        }
        return sum
    }

    private fun releaseFilter() {
        filterCoefficients = FloatArray(0)
        filterTaps = 0
        filterHistory = FloatArray(0)
        filterHistoryPos = 0
        filterProcessedFrame = -1
        filterCacheIndex = -1
        filterCache = FloatArray(0)
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

        /** Stopband target with margin over the 60 dB the output Nyquist requires. */
        const val FILTER_ATTENUATION_DB = 70.0

        /** A hard ceiling so a pathological target rate cannot allocate without bound. */
        const val MAX_FILTER_TAPS = 4_095

        /**
         * A Kaiser-windowed sinc low-pass for [inputRateHz] -> [outputRateHz].
         *
         * The passband corner is 0.45 of the output rate, so the stopband can
         * begin exactly at the output Nyquist (0.5 of the output rate). The
         * filter length follows Kaiser's estimate for [FILTER_ATTENUATION_DB];
         * that leaves the design with roughly 10 dB of margin at the Nyquist
         * edge. Returns null when the output rate is not lower, because there
         * is nothing to alias in an upsample.
         */
        fun designLowPass(inputRateHz: Int, outputRateHz: Int): FloatArray? {
            if (outputRateHz >= inputRateHz) return null
            val cutoffHz = 0.45 * outputRateHz
            val stopHz = 0.5 * outputRateHz
            val transitionHz = stopHz - cutoffHz
            if (transitionHz <= 0.0) return null
            val transition = transitionHz / inputRateHz
            var taps = ceil(
                (FILTER_ATTENUATION_DB - 8.0) /
                    (2.285 * 2.0 * PI * transition),
            ).toInt()
            taps = taps.coerceAtMost(MAX_FILTER_TAPS)
            if (taps % 2 == 0) taps += 1
            taps = max(taps, 3)

            val beta = 0.1102 * (FILTER_ATTENUATION_DB - 8.7)
            val windowScale = besselI0(beta)
            val center = (taps - 1) / 2.0
            val normalizedCutoff = cutoffHz / inputRateHz
            val coefficients = FloatArray(taps)
            var sum = 0.0
            for (tap in 0 until taps) {
                val offset = tap - center
                val sinc = if (abs(offset) < 1e-12) {
                    2.0 * normalizedCutoff
                } else {
                    sin(2.0 * PI * normalizedCutoff * offset) / (PI * offset)
                }
                val ratio = 2.0 * tap / (taps - 1) - 1.0
                val window = besselI0(beta * sqrt(max(0.0, 1.0 - ratio * ratio))) /
                    windowScale
                val value = sinc * window
                coefficients[tap] = value.toFloat()
                sum += value
            }
            if (abs(sum) > 1e-12) {
                for (tap in coefficients.indices) {
                    coefficients[tap] = (coefficients[tap] / sum).toFloat()
                }
            }
            return coefficients
        }

        /** Zeroth-order modified Bessel function, for the Kaiser window. */
        fun besselI0(value: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = value / 2.0
            var order = 1
            while (order < 100) {
                term *= (half / order) * (half / order)
                sum += term
                if (term <= 1e-15 * sum) break
                order += 1
            }
            return sum
        }
    }
}
