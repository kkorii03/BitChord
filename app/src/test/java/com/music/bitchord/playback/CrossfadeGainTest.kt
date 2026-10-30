package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossfadeGainTest {

    @Test
    fun startsAtFullOutgoingAndSilentIncoming() {
        val gains = CrossfadeGain.gains(0f)
        assertEquals(0f, gains.incoming, 0.0001f)
        assertEquals(1f, gains.outgoing, 0.0001f)
    }

    @Test
    fun productionMixFadersAreTheEqualPowerPairAtEveryPoint() {
        assertEquals(0f, CrossfadeGain.mixRise(0f), 0.0001f)
        assertEquals(1f, CrossfadeGain.mixFall(0f), 0.0001f)
        assertEquals(0.7071f, CrossfadeGain.mixRise(0.5f), 0.0001f)
        assertEquals(0.7071f, CrossfadeGain.mixFall(0.5f), 0.0001f)
        assertEquals(1f, CrossfadeGain.mixRise(1f), 0.0001f)
        assertEquals(0f, CrossfadeGain.mixFall(1f), 0.0001f)
        var progress = 0f
        while (progress <= 1f) {
            assertEquals(CrossfadeGain.rise(progress), CrossfadeGain.mixRise(progress), 0f)
            assertEquals(CrossfadeGain.fall(progress), CrossfadeGain.mixFall(progress), 0f)
            progress += 0.05f
        }
    }

    @Test
    fun midpointIsMinusThreeDbOnBothSides() {
        val gains = CrossfadeGain.gains(0.5f)
        assertEquals(0.7071f, gains.incoming, 0.0001f)
        assertEquals(0.7071f, gains.outgoing, 0.0001f)
        assertEquals(1f, gains.incoming * gains.incoming + gains.outgoing * gains.outgoing, 0.0001f)
    }

    @Test
    fun endsAtSilentOutgoingAndFullIncoming() {
        val gains = CrossfadeGain.gains(1f)
        assertEquals(1f, gains.incoming, 0.0001f)
        assertEquals(0f, gains.outgoing, 0.0001f)
    }

    @Test
    fun constantPowerHoldsAcrossTheWholeFade() {
        var progress = 0f
        while (progress <= 1f) {
            val gains = CrossfadeGain.gains(progress)
            assertEquals(1f, gains.incoming * gains.incoming + gains.outgoing * gains.outgoing, 0.0001f)
            progress += 0.05f
        }
    }

    @Test
    fun progressReachesOneExactlyAtThePlannedFadeLength() {
        assertEquals(0f, CrossfadeGain.progress(0L, 6_000L), 0.0001f)
        assertEquals(0.25f, CrossfadeGain.progress(1_500L, 6_000L), 0.0001f)
        assertEquals(1f, CrossfadeGain.progress(6_000L, 6_000L), 0.0001f)
        assertEquals(1f, CrossfadeGain.progress(9_000L, 6_000L), 0.0001f)
    }

    @Test
    fun spanKeepsThePlannedFadeUnlessTheIncomingTrackIsTooShort() {
        assertEquals(6_000L, CrossfadeGain.spanMs(6_000L, null))
        assertEquals(6_000L, CrossfadeGain.spanMs(6_000L, 60_000L))
        assertEquals(2_000L, CrossfadeGain.spanMs(6_000L, 6_000L))
        assertEquals(1L, CrossfadeGain.spanMs(0L, 60_000L))
    }
}
