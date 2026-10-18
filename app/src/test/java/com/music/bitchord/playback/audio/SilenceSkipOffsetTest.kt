package com.music.bitchord.playback.audio

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SilenceSkipOffsetTest {

    @Test
    fun `skipped frames advance the output stream offset by their media duration`() {
        val adjusted = SilenceSkipOffset.adjustedOutputStreamOffsetUs(
            outputStreamOffsetUs = 5_000_000L,
            skippedFrames = 48_000L,
            sampleRate = 48_000,
        )

        assertEquals(6_000_000L, adjusted)
    }

    @Test
    fun `a partial second maps with the renderer's sample count conversion`() {
        val frames = 12_345L
        val rate = 44_100

        val adjusted = SilenceSkipOffset.adjustedOutputStreamOffsetUs(
            outputStreamOffsetUs = 1_000_000L,
            skippedFrames = frames,
            sampleRate = rate,
        )

        assertEquals(
            1_000_000L + Util.sampleCountToDurationUs(frames, rate),
            adjusted,
        )
    }

    @Test
    fun `no skipped frames leaves the offset untouched`() {
        assertEquals(
            8_000_000L,
            SilenceSkipOffset.adjustedOutputStreamOffsetUs(
                outputStreamOffsetUs = 8_000_000L,
                skippedFrames = 0L,
                sampleRate = 48_000,
            ),
        )
    }

    @Test
    fun `an unset offset is not shifted`() {
        assertEquals(
            C.TIME_UNSET,
            SilenceSkipOffset.adjustedOutputStreamOffsetUs(
                outputStreamOffsetUs = C.TIME_UNSET,
                skippedFrames = 48_000L,
                sampleRate = 48_000,
            ),
        )
    }
}
