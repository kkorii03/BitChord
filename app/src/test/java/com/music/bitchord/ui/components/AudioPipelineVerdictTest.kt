package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bit-exact readout names the first stage that alters the samples, in the
 * same order the signal meets them. The old string version returned "Yes" in
 * English inside a Korean dialog; this keeps the truth table while making the
 * label localisable.
 */
class AudioPipelineVerdictTest {

    @Test
    fun dsdWinsOverEveryLaterStage() {
        assertEquals(
            BitExactVerdict.DsdToPcm,
            bitExactVerdict(
                dsd = true,
                outputExact = false,
                loudnessActive = true,
                eqActive = true,
                spatialActive = true,
            ),
        )
    }

    @Test
    fun theFirstActiveDspStageIsNamed() {
        assertEquals(
            BitExactVerdict.Loudness,
            bitExactVerdict(false, false, loudnessActive = true, eqActive = true, spatialActive = true),
        )
        assertEquals(
            BitExactVerdict.Equalizer,
            bitExactVerdict(false, false, loudnessActive = false, eqActive = true, spatialActive = true),
        )
        assertEquals(
            BitExactVerdict.Spatial,
            bitExactVerdict(false, false, loudnessActive = false, eqActive = false, spatialActive = true),
        )
    }

    @Test
    fun anIdleDspChainFallsThroughToTheSinkVerdict() {
        assertEquals(
            BitExactVerdict.Exact,
            bitExactVerdict(false, outputExact = true, loudnessActive = false, eqActive = false, spatialActive = false),
        )
        assertEquals(
            BitExactVerdict.Downstream,
            bitExactVerdict(false, outputExact = false, loudnessActive = false, eqActive = false, spatialActive = false),
        )
    }
}
