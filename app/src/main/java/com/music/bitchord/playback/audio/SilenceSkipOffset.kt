package com.music.bitchord.playback.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/**
 * Maps skipped output frames back onto the media clock.
 *
 * [PrecisionAudioSink] hands the delegate media presentation timestamps while
 * writing fewer frames than it consumed. `DefaultAudioSink` derives its position
 * from the frames it actually wrote plus the output stream offset, so the cut
 * duration has to be folded into that offset or lyrics and seek drift behind the
 * sound. Frames are converted in one go rather than per block to avoid rounding
 * drift over a long gap.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object SilenceSkipOffset {

    fun adjustedOutputStreamOffsetUs(
        outputStreamOffsetUs: Long,
        skippedFrames: Long,
        sampleRate: Int,
    ): Long {
        if (outputStreamOffsetUs == C.TIME_UNSET || skippedFrames <= 0L || sampleRate <= 0) {
            return outputStreamOffsetUs
        }
        return outputStreamOffsetUs + Util.sampleCountToDurationUs(skippedFrames, sampleRate)
    }
}
