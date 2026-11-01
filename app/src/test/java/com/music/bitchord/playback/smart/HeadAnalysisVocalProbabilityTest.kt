package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A head-only pass is the result the incoming side of a transition usually has
 * when it is planned. Dropping its vocal estimate made the planner read "no
 * vocal evidence" exactly when it had measured some, so the speech/vocal
 * overlap guard could not see the opening it was asked about.
 */
class HeadAnalysisVocalProbabilityTest {

    private fun features(vocalProbability: Double) = TrackFeatures.Features(
        duration = 180.0,
        bpm = 120.0,
        beatInterval = 0.5,
        firstBeat = 0.25,
        beatConfidence = 0.9,
        key = "C",
        keyConfidence = 0.8,
        loudnessLufs = -9.5,
        audibleStartTime = 0.0,
        pickupTime = 0.0,
        introEndTime = 12.0,
        outroStartTime = 160.0,
        contentEndTime = 175.0,
        mixInTime = 8.0,
        mixOutTime = 168.0,
        vocalProbability = vocalProbability,
        downbeats = emptyList(),
        phraseBoundaries = emptyList(),
        vocalActivityMask = emptyList(),
        energyCurve = emptyList(),
        lowEnergyCurve = emptyList(),
        mixInCandidates = emptyList(),
        mixOutCandidates = emptyList(),
    )

    @Test
    fun `a head pass keeps the vocal probability it measured`() {
        val analysis = headAnalysis(
            trackId = "track",
            durationSeconds = 180.0,
            grid = null,
            entry = features(vocalProbability = 0.82),
        )

        assertEquals(0.82, analysis.vocalProbability, 0.0)
        assertTrue(analysis.isUsable)
    }

    @Test
    fun `a head pass without a vocal reading reports no evidence`() {
        val analysis = headAnalysis(
            trackId = "track",
            durationSeconds = 180.0,
            grid = null,
            entry = features(vocalProbability = 0.0),
        )

        assertEquals(0.0, analysis.vocalProbability, 0.0)
    }
}
