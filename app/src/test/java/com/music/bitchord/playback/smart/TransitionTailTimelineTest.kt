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

    // Review 11-18: the tail was shifted by the requested start, but its first
    // decoded sample (SEEK_TO_CLOSEST_SYNC, WebM) can be earlier, and the beat
    // grid is stated against that sample. The tail read late against the grid.
    @Test
    fun `tail features are shifted by the first decoded sample, not the requested start`() {
        val requested = 255.0
        val actualStart = 254.2
        val offset = regionFeatureOffset(requested, actualStart)
        val shifted = features(contentEnd = 23.5).shiftedBy(offset)
        assertEquals(actualStart + 23.5, shifted.contentEndTime, 1e-6)
        assertEquals(actualStart + 30.0, shifted.outroStartTime, 1e-6)
        // The head asks for no shift and gets none.
        assertEquals(0.0, regionFeatureOffset(0.0, 0.02), 0.0)
        var root = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (!java.io.File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("repo root not found")
        val source = java.io.File(root, "app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt").readText()
        assertEquals(
            "region() must shift by the decoded start",
            true,
            "inputs.derived?.shiftedBy(regionFeatureOffset(featureOffsetSeconds, actualStart))" in source,
        )
    }
}
