package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeArmPolicyBoundaryTest {

    @Test
    fun theArmTimeoutIsThreeSecondsPlusANonNegativeRamp() {
        assertEquals(3_000L, CrossfadeArmPolicy.ARM_TIMEOUT_MS)
        assertEquals(3_000L, CrossfadeArmPolicy.armTimeoutMs())
        assertEquals(3_000L, CrossfadeArmPolicy.armTimeoutMs(0L))
        assertEquals(3_000L, CrossfadeArmPolicy.armTimeoutMs(-1L))
        assertEquals(3_001L, CrossfadeArmPolicy.armTimeoutMs(1L))
        assertEquals(5_500L, CrossfadeArmPolicy.armTimeoutMs(2_500L))
    }

    @Test
    fun anUnknownFormatNeverBlocksArming() {
        assertTrue(CrossfadeArmPolicy.canMix(null, null))
        assertTrue(CrossfadeArmPolicy.canMix(null, CrossfadeTrackFormat()))
        assertTrue(CrossfadeArmPolicy.canMix(CrossfadeTrackFormat(), null))
    }

    @Test
    fun anUnknownFormatIsNotJudgedByItsOtherSideBeingUndecodable() {
        val passthrough = CrossfadeTrackFormat(decodable = false)
        assertTrue(CrossfadeArmPolicy.canMix(null, passthrough))
        assertTrue(CrossfadeArmPolicy.canMix(passthrough, null))
    }

    @Test
    fun twoOrdinaryTracksAtDifferentRatesStillMix() {
        val opus48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val flac441 = CrossfadeTrackFormat(sampleRateHz = 44_100, channelCount = 2)
        assertTrue(CrossfadeArmPolicy.canMix(opus48, flac441))
        assertTrue(CrossfadeArmPolicy.canMix(flac441, opus48))
    }

    @Test
    fun twoDirectTracksAtTheSameRateMix() {
        val first = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        val second = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        assertTrue(CrossfadeArmPolicy.canMix(first, second))
    }

    @Test
    fun aDirectTrackAtADifferentRateIsRefusedInEitherDirection() {
        val direct48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        val ordinary441 = CrossfadeTrackFormat(sampleRateHz = 44_100, channelCount = 2)
        val direct441 = CrossfadeTrackFormat(sampleRateHz = 44_100, channelCount = 2, direct = true)
        assertFalse(CrossfadeArmPolicy.canMix(direct48, ordinary441))
        assertFalse(CrossfadeArmPolicy.canMix(ordinary441, direct48))
        assertFalse(CrossfadeArmPolicy.canMix(direct48, direct441))
    }

    @Test
    fun aDirectTrackWithAnUnknownRateIsAllowed() {
        val direct48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        val unknownRate = CrossfadeTrackFormat(sampleRateHz = null, channelCount = 2)
        assertTrue(CrossfadeArmPolicy.canMix(direct48, unknownRate))
        assertTrue(CrossfadeArmPolicy.canMix(unknownRate, direct48))
    }

    @Test
    fun aChannelMismatchIsRefusedEvenWhenTheRateMatches() {
        val stereo = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val surround = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 6)
        assertFalse(CrossfadeArmPolicy.canMix(stereo, surround))
        assertFalse(CrossfadeArmPolicy.canMix(surround, stereo))
    }

    @Test
    fun anUnknownChannelCountNeverBlocksTheMix() {
        val stereo = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val unknown = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = null)
        assertTrue(CrossfadeArmPolicy.canMix(stereo, unknown))
        assertTrue(CrossfadeArmPolicy.canMix(unknown, stereo))
    }

    @Test
    fun aNonDecodableStreamIsRefusedInEitherDirection() {
        val pcm = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2)
        val passthrough = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, decodable = false)
        assertFalse(CrossfadeArmPolicy.canMix(pcm, passthrough))
        assertFalse(CrossfadeArmPolicy.canMix(passthrough, pcm))
        assertFalse(CrossfadeArmPolicy.canMix(passthrough, passthrough))
    }

    @Test
    fun aNonDecodableStreamIsRefusedBeforeTheChannelCheck() {
        val passthrough = CrossfadeTrackFormat(channelCount = 2, decodable = false)
        val surround = CrossfadeTrackFormat(channelCount = 6)
        assertFalse(CrossfadeArmPolicy.canMix(passthrough, surround))
        assertFalse(CrossfadeArmPolicy.canMix(surround, passthrough))
    }

    @Test
    fun aZeroOrNegativeRateIsStillComparedWhenKnown() {
        val directZero = CrossfadeTrackFormat(sampleRateHz = 0, channelCount = 2, direct = true)
        val direct48 = CrossfadeTrackFormat(sampleRateHz = 48_000, channelCount = 2, direct = true)
        assertFalse(CrossfadeArmPolicy.canMix(directZero, direct48))

        val directZeroOther = CrossfadeTrackFormat(sampleRateHz = 0, channelCount = 2, direct = true)
        assertTrue(CrossfadeArmPolicy.canMix(directZero, directZeroOther))

        val negative = CrossfadeTrackFormat(sampleRateHz = -1, channelCount = 2, direct = true)
        assertFalse(CrossfadeArmPolicy.canMix(negative, direct48))
    }

    @Test
    fun twoFullyUnknownFormatsAreAllowed() {
        assertTrue(CrossfadeArmPolicy.canMix(CrossfadeTrackFormat(), CrossfadeTrackFormat()))
    }
}
