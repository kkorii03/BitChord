package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeArmPolicyTest {

    @Test
    fun armTimeoutIsShortAndIncludesTheTempoRamp() {
        assertEquals(3_000L, CrossfadeArmPolicy.ARM_TIMEOUT_MS)
        assertEquals(3_000L, CrossfadeArmPolicy.armTimeoutMs())
        assertEquals(4_500L, CrossfadeArmPolicy.armTimeoutMs(rampMs = 1_500L))
        assertEquals(3_000L, CrossfadeArmPolicy.armTimeoutMs(rampMs = -1_000L))
    }

    @Test
    fun unknownFormatsAreAllowedSoArmingCanDiscoverThem() {
        assertTrue(CrossfadeArmPolicy.canMix(null, CrossfadeTrackFormat(sampleRateHz = 48_000)))
        assertTrue(CrossfadeArmPolicy.canMix(CrossfadeTrackFormat(), null))
        assertTrue(CrossfadeArmPolicy.canMix(null, null))
    }

    @Test
    fun ordinaryRateDifferenceIsMixableByThePlatformMixer() {
        val opus48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val flac441 = CrossfadeTrackFormat(sampleRateHz = 44_100, channelCount = 2)
        assertTrue(CrossfadeArmPolicy.canMix(opus48, flac441))
        assertTrue(CrossfadeArmPolicy.canMix(flac441, opus48))
    }

    @Test
    fun twoDirectTracksAtDifferentRatesCannotBeSummed() {
        val direct48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        val direct441 = CrossfadeTrackFormat(sampleRateHz = 44_100, channelCount = 2, direct = true)
        assertFalse(CrossfadeArmPolicy.canMix(direct48, direct441))
        assertFalse(CrossfadeArmPolicy.canMix(direct48, CrossfadeTrackFormat(sampleRateHz = 44_100)))
    }

    @Test
    fun directTracksAtTheSameRateStillMix() {
        val direct48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        val direct48b = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        assertTrue(CrossfadeArmPolicy.canMix(direct48, direct48b))
    }

    @Test
    fun channelLayoutMismatchIsRefused() {
        val stereo = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val surround = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 6)
        assertFalse(CrossfadeArmPolicy.canMix(stereo, surround))
    }

    @Test
    fun nonDecodableStreamIsRefused() {
        val pcm = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val passthrough = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, decodable = false)
        assertFalse(CrossfadeArmPolicy.canMix(pcm, passthrough))
        assertFalse(CrossfadeArmPolicy.canMix(passthrough, pcm))
    }

    @Test
    fun aSkipBeforeTheHandoffSilencesTheArmedIncomingTrack() {
        assertTrue(CrossfadeArmPolicy.skipSilencesIncoming(isFading = true, handedOff = false))
        assertFalse(CrossfadeArmPolicy.skipSilencesIncoming(isFading = true, handedOff = true))
        assertFalse(CrossfadeArmPolicy.skipSilencesIncoming(isFading = false, handedOff = false))
        assertFalse(CrossfadeArmPolicy.skipSilencesIncoming(isFading = false, handedOff = true))
    }
}
