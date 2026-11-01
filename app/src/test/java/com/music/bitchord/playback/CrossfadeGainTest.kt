package com.music.bitchord.playback

import com.music.bitchord.ko.KoCrossfadeGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeGainTest {

    @Test
    fun startsAtFullOutgoingAndSilentIncoming() {
        val gains = KoCrossfadeGain.gains(0f)
        assertEquals(0f, gains.incoming, 0.0001f)
        assertEquals(1f, gains.outgoing, 0.0001f)
    }

    @Test
    fun productionMixFadersAreTheEqualPowerPairAtEveryPoint() {
        assertEquals(0f, KoCrossfadeGain.mixRise(0f), 0.0001f)
        assertEquals(1f, KoCrossfadeGain.mixFall(0f), 0.0001f)
        assertEquals(0.7071f, KoCrossfadeGain.mixRise(0.5f), 0.0001f)
        assertEquals(0.7071f, KoCrossfadeGain.mixFall(0.5f), 0.0001f)
        assertEquals(1f, KoCrossfadeGain.mixRise(1f), 0.0001f)
        assertEquals(0f, KoCrossfadeGain.mixFall(1f), 0.0001f)
        var progress = 0f
        while (progress <= 1f) {
            assertEquals(KoCrossfadeGain.rise(progress), KoCrossfadeGain.mixRise(progress), 0f)
            assertEquals(KoCrossfadeGain.fall(progress), KoCrossfadeGain.mixFall(progress), 0f)
            progress += 0.05f
        }
    }

    @Test
    fun midpointIsMinusThreeDbOnBothSides() {
        val gains = KoCrossfadeGain.gains(0.5f)
        assertEquals(0.7071f, gains.incoming, 0.0001f)
        assertEquals(0.7071f, gains.outgoing, 0.0001f)
        assertEquals(1f, gains.incoming * gains.incoming + gains.outgoing * gains.outgoing, 0.0001f)
    }

    @Test
    fun endsAtSilentOutgoingAndFullIncoming() {
        val gains = KoCrossfadeGain.gains(1f)
        assertEquals(1f, gains.incoming, 0.0001f)
        assertEquals(0f, gains.outgoing, 0.0001f)
    }

    @Test
    fun constantPowerHoldsAcrossTheWholeFade() {
        var progress = 0f
        while (progress <= 1f) {
            val gains = KoCrossfadeGain.gains(progress)
            assertEquals(1f, gains.incoming * gains.incoming + gains.outgoing * gains.outgoing, 0.0001f)
            progress += 0.05f
        }
    }

    @Test
    fun progressReachesOneExactlyAtThePlannedFadeLength() {
        assertEquals(0f, KoCrossfadeGain.progress(0L, 6_000L), 0.0001f)
        assertEquals(0.25f, KoCrossfadeGain.progress(1_500L, 6_000L), 0.0001f)
        assertEquals(1f, KoCrossfadeGain.progress(6_000L, 6_000L), 0.0001f)
        assertEquals(1f, KoCrossfadeGain.progress(9_000L, 6_000L), 0.0001f)
    }

    @Test
    fun spanKeepsThePlannedFadeUnlessTheIncomingTrackIsTooShort() {
        assertEquals(6_000L, KoCrossfadeGain.spanMs(6_000L, null))
        assertEquals(6_000L, KoCrossfadeGain.spanMs(6_000L, 60_000L))
        assertEquals(2_000L, KoCrossfadeGain.spanMs(6_000L, 6_000L))
        assertEquals(1L, KoCrossfadeGain.spanMs(0L, 60_000L))
    }

    @Test
    fun bailSurvivorReturnsFromTheFadedLevelToUnityWithoutSnapping() {
        assertEquals(0.2f, KoCrossfadeGain.bailSurvivor(0.2f, 0f), 0.0001f)
        assertEquals(1f, KoCrossfadeGain.bailSurvivor(0.2f, 1f), 0.0001f)
        assertEquals(1f, KoCrossfadeGain.bailSurvivor(1f, 0f), 0.0001f)
        assertEquals(0f, KoCrossfadeGain.bailSurvivor(0f, 0f), 0.0001f)
        var last = KoCrossfadeGain.bailSurvivor(0.2f, 0f)
        var progress = 0.05f
        while (progress <= 1f) {
            val gain = KoCrossfadeGain.bailSurvivor(0.2f, progress)
            assertTrue("bail gain went backwards at $progress", gain >= last)
            last = gain
            progress += 0.05f
        }
    }

    @Test
    fun aSkipBeforeTheHandoffRampsTheArmedIncomingToSilenceInsteadOfSnapping() {
        // The old skip path assigned `volume = 0f` from whatever the blend
        // gain was. This is the ramp that replaces it: it starts at the real
        // gain and reaches zero in a few milliseconds, never stepping.
        val from = KoCrossfadeGain.mixRise(0.35f)
        val span = KoCrossfadeGain.SKIP_SILENCE_MS
        assertEquals(from, KoCrossfadeGain.skipSilence(from, elapsedMs = 0L, spanMs = span), 0.0001f)
        val quarter = KoCrossfadeGain.skipSilence(from, elapsedMs = span / 4, spanMs = span)
        assertTrue("skip silence snapped instead of ramping", quarter > 0f && quarter < from)
        assertEquals(0f, KoCrossfadeGain.skipSilence(from, elapsedMs = span, spanMs = span), 0.0001f)
        assertEquals(0f, KoCrossfadeGain.skipSilence(from, elapsedMs = 100L, spanMs = span), 0.0001f)
        var last = KoCrossfadeGain.skipSilence(from, elapsedMs = 0L, spanMs = span)
        var elapsed = 1L
        while (elapsed <= span) {
            val gain = KoCrossfadeGain.skipSilence(from, elapsedMs = elapsed, spanMs = span)
            assertTrue("skip silence went forwards at $elapsed", gain <= last)
            last = gain
            elapsed++
        }
    }

    @Test
    fun theSkipSilenceRampOutlivesTheRealBailTickCadence() {
        // driveBail samples every BAIL_STEP_MS (15 ms). The ramp used to be
        // 8 ms, so the first sample was full gain and the next at 15 ms was
        // already zero: the step it was meant to remove, just one tick late.
        val tick = 15L
        val from = KoCrossfadeGain.mixRise(0.5f)
        val span = KoCrossfadeGain.SKIP_SILENCE_MS
        assertTrue("skip ramp must span more than one bail tick", span > tick)
        val samples = mutableListOf<Float>()
        var elapsed = 0L
        while (elapsed <= span) {
            samples += KoCrossfadeGain.skipSilence(from, elapsed, span)
            elapsed += tick
        }
        val intermediate = samples.count { it > 0f && it < from }
        assertTrue("skip ramp had no intermediate sample at $tick ms ticks: $samples", intermediate >= 2)
        assertEquals(0f, samples.last(), 0.0001f)
    }
}
