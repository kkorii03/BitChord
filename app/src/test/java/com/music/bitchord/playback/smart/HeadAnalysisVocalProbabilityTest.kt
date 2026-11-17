package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // c2-analyzer-crossfade-1: an unknown duration (0) gave min(45, 0) = 0, an
    // empty decode range, and two such failures wrote HEAD off for the session.
    @Test
    fun `an unknown duration still decodes the full opening window`() {
        assertEquals(AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS, headWindowSeconds(0.0), 0.0)
        assertEquals(AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS, headWindowSeconds(Double.NaN), 0.0)
        assertEquals(30.0, headWindowSeconds(30.0), 0.0)
        assertEquals(AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS, headWindowSeconds(200.0), 0.0)
    }

    @Test
    fun `a failed pass over a partial download does not count toward the write-off`() {
        assertFalse(windowFailureCounts(partialRendition = true))
        assertTrue(windowFailureCounts(partialRendition = false))
    }

    // c2-analyzer-crossfade-2: a written-off HEAD counted as measured, so a
    // tail-only merge was stored and blocked HEAD in every later session.
    @Test
    fun `only a merge with both windows really measured is stored`() {
        val tail = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "track", bpm = 120.0)
        val head = tail.copy(audibleStartTime = 0.2)
        assertFalse(persistableMerge(head = null, tail = tail))
        assertFalse(persistableMerge(head = head, tail = null))
        assertTrue(persistableMerge(head = head, tail = tail))
    }

    // c2-analyzer-crossfade-3: the merge kept the tail's downbeats only, so the
    // incoming track's opening had no bar to snap its cue to.
    @Test
    fun `the merged analysis keeps the opening's downbeats and first beat`() {
        val head = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "track",
            duration = 240.0,
            bpm = 120.0,
            beatInterval = 0.5,
            downbeats = listOf(1.0, 3.0),
            firstBeat = 1.0,
            audibleStartTime = 0.4,
        )
        val tail = head.copy(downbeats = listOf(200.0, 202.0), firstBeat = 199.5, audibleStartTime = null)

        val merged = mergeWindowResults("track", head, tail, 240.0)

        assertEquals(listOf(1.0, 3.0, 200.0, 202.0), merged.downbeats)
        assertEquals(1.0, merged.firstBeat, 0.0)
        assertTrue(1.0 in merged.phraseBoundaries)
    }

    @Test
    fun `overlapping windows of a short track do not repeat a bar`() {
        val merged = mergeWindowDownbeats(
            head = listOf(1.0, 3.0, 5.0, 7.0),
            tail = listOf(4.98, 7.01),
            beatInterval = 0.5,
        )

        assertEquals(listOf(1.0, 3.0, 4.98, 7.01), merged)
    }
}
