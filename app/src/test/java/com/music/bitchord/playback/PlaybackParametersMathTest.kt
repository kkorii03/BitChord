package com.music.bitchord.playback

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackParametersMathTest {
    @Test
    fun zeroSemitonesLeavePitchUnchanged() {
        assertEquals(1f, PlaybackParametersMath.pitchForSemitones(0), 1e-6f)
    }

    @Test
    fun semitonesUseEqualTemperedRatios() {
        assertEquals(2.0.pow(3 / 12.0).toFloat(), PlaybackParametersMath.pitchForSemitones(3), 1e-6f)
        assertEquals(2.0.pow(6 / 12.0).toFloat(), PlaybackParametersMath.pitchForSemitones(6), 1e-6f)
        assertEquals(2.0.pow(-6 / 12.0).toFloat(), PlaybackParametersMath.pitchForSemitones(-6), 1e-6f)
    }

    @Test
    fun semitonesClampToTheSupportedRange() {
        assertEquals(6, PlaybackParametersMath.clampPitchSemitones(42))
        assertEquals(-6, PlaybackParametersMath.clampPitchSemitones(-42))
        assertEquals(
            PlaybackParametersMath.pitchForSemitones(6),
            PlaybackParametersMath.pitchForSemitones(9),
            0f,
        )
        assertEquals(
            PlaybackParametersMath.pitchForSemitones(-6),
            PlaybackParametersMath.pitchForSemitones(-9),
            0f,
        )
    }

    @Test
    fun parametersKeepSpeedAndCarryTheMappedPitch() {
        val parameters = PlaybackParametersMath.parameters(speed = 1.25f, semitones = 3)
        assertEquals(1.25f, parameters.speed, 0f)
        assertEquals(PlaybackParametersMath.pitchForSemitones(3), parameters.pitch, 0f)
    }
}
