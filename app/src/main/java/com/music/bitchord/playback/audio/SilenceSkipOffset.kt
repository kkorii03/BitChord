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

    /**
     * The same conversion, in whole milliseconds, for diagnostics. The lyric
     * sync log states how much audio has been cut so a late line can be told
     * apart from a position that simply does not include the cut.
     */
    fun skippedMillis(skippedFrames: Long, sampleRate: Int): Long {
        if (skippedFrames <= 0L || sampleRate <= 0) return 0L
        return Util.sampleCountToDurationUs(skippedFrames, sampleRate) / 1_000L
    }
}

/**
 * How much silence has been cut from the stream currently being written,
 * published for the diagnostics lines. Written by [PrecisionAudioSink] on the
 * audio path and read by the logger on its own thread; every field is volatile
 * and nothing here schedules work.
 */
object SilenceSkipStats {
    @Volatile
    var skippedMs: Long = 0L
        private set

    fun set(skippedFrames: Long, sampleRate: Int) {
        skippedMs = SilenceSkipOffset.skippedMillis(skippedFrames, sampleRate)
    }

    fun reset() {
        skippedMs = 0L
    }
}
