package com.music.bitchord.playback

import com.music.bitchord.playback.smart.VersionSwapPolicy
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun repeatModeFromTheSessionIsPersistedOnlyWhenItChanged() {
        // The notification/car path sets the mode on the player, not through
        // MainActivity, so this is what makes it survive a restart.
        assertTrue(PlaybackParametersMath.shouldPersistRepeatMode(stored = 0, incoming = 2))
        assertTrue(PlaybackParametersMath.shouldPersistRepeatMode(stored = 2, incoming = 1))
        assertFalse(PlaybackParametersMath.shouldPersistRepeatMode(stored = 2, incoming = 2))
        // Nothing outside Media3's three modes is a listener choice.
        assertFalse(PlaybackParametersMath.shouldPersistRepeatMode(stored = 0, incoming = 9))
    }

    @Test
    fun versionSwapRefusesCoverLevelConfidence() {
        // Field log: a cover measured 0.4557 and was swapped in anyway.
        assertFalse(VersionSwapPolicy.shouldSwap(0.4557))
        assertFalse(VersionSwapPolicy.shouldSwap(0.4546))
        assertFalse(VersionSwapPolicy.shouldSwap(0.0))
        assertFalse(VersionSwapPolicy.shouldSwap(0.599999))
    }

    @Test
    fun versionSwapAcceptsMatchingRecordings() {
        assertEquals(0.6, VersionSwapPolicy.MIN_SWAP_CONFIDENCE, 0.0)
        assertTrue(VersionSwapPolicy.shouldSwap(0.6))
        assertTrue(VersionSwapPolicy.shouldSwap(0.851))
        assertTrue(VersionSwapPolicy.shouldSwap(0.999))
    }
}
