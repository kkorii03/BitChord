package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A tail window's features are measured in the window's own 0..45 s timeline.
 * They must be shifted onto the track timeline before the planner reads them,
 * or a five-minute song's content end reads as ~24 s and Automix fades it out
 * seconds after it starts.
 */
class TransitionTailTimelineTest {

    private fun features(contentEnd: Double) = TrackFeatures.Features(
        duration = 45.0,
        bpm = 120.0,
        beatInterval = 0.5,
        firstBeat = 0.5,
        beatConfidence = 0.9,
        key = "C major",
        keyConfidence = 0.9,
        loudnessLufs = -10.0,
        audibleStartTime = 0.0,
        pickupTime = 0.0,
        introEndTime = 0.0,
        outroStartTime = 30.0,
        contentEndTime = contentEnd,
        mixInTime = 0.0,
        mixOutTime = contentEnd - 2.0,
        vocalProbability = 0.1,
        downbeats = listOf(0.5, 2.5, 4.5),
        phraseBoundaries = listOf(0.5, 8.5),
        vocalActivityMask = emptyList(),
        energyCurve = listOf(EnergySample(0.0, 1.0), EnergySample(44.0, 1.0)),
        lowEnergyCurve = emptyList(),
        mixInCandidates = emptyList(),
        mixOutCandidates = listOf(MixCandidate(20.0, 0.9, "outro_start")),
    )

    @Test
    fun `tail features move onto the track timeline`() {
        val tailStart = 255.0
        val shifted = features(contentEnd = 23.5).shiftedBy(tailStart)
        assertEquals(278.5, shifted.contentEndTime, 1e-6)
        assertEquals(276.5, shifted.mixOutTime, 1e-6)
        assertEquals(275.0, shifted.mixOutCandidates.single().time, 1e-6)
        assertEquals(285.0, shifted.outroStartTime, 1e-6)
    }
}
