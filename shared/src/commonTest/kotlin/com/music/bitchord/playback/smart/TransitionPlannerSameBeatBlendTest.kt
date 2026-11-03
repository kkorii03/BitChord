package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The v1.9 planner only takes the beat-matched blend when every old term
 * agrees: a blendable tempo, a confident grid, and — when the tempi actually
 * need a stretch — both grids over the beat-match floor. A later rewrite of
 * `sameBeatBlend` dropped the confidence floor and the beat-match-trust term,
 * so a 3% stretch on two weak grids (0.25) took the blend branch and played
 * two unstretched tempi over each other. It must stay a filter ride.
 */
class TransitionPlannerSameBeatBlendTest {

    private fun track(id: String, bpm: Double, confidence: Double = 0.9): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < 199.0 }.toList()
        val energy = (0 until 400).map { EnergySample(it / 2.0, 1.0) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = 200.0,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = confidence,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = 198.0,
            mixInTime = downbeats[4],
            energyCurve = energy,
            vocalProbability = 0.1,
        )
    }

    private fun plan(current: TrackAnalysis, next: TrackAnalysis) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, 200_000),
        nextTrack = TransitionTrackInfo(next.trackId, 200_000),
        currentTime = 10.0,
        duration = 200.0,
        mode = CrossfadeMode.SMART,
        advanced = true,
    )

    @Test
    fun `a small stretch on weak grids is filtered, not beat-matched`() {
        val plan = plan(track("out", 120.0, 0.25), track("in", 123.6, 0.25))
        assertFalse(plan.beatAligned, "0.25 grids are below the beat-match floor")
        assertEquals(1.0, plan.incomingPlaybackRate, "no stretch without trusted grids")
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
    }

    @Test
    fun `a small stretch on trusted grids still beat-matches`() {
        val plan = plan(track("out", 120.0), track("in", 123.6))
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
    }
}
