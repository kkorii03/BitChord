package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the automatic-mix review of 10-31.
 *
 * Each test pins a case where the planner used to promise something the
 * renderer could not honour, or trusted evidence the policy had already
 * declared unusable.
 */
class AutomixSafety1031Test {

    private fun track(
        id: String,
        bpm: Double,
        beatConfidence: Double = 0.9,
        vocal: Double = 0.1,
        duration: Double = 200.0,
        contentEnd: Double = duration - 2,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        val energy = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, 1.0) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = beatConfidence,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = contentEnd,
            mixInTime = downbeats[4],
            energyCurve = energy,
            vocalProbability = vocal,
        )
    }

    private fun plan(
        current: TrackAnalysis,
        next: TrackAnalysis,
        advanced: Boolean = true,
        phraseBars: Int = 0,
    ) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, (current.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
        currentTime = 10.0,
        duration = current.duration,
        mode = CrossfadeMode.SMART,
        advanced = advanced,
        phraseBars = phraseBars,
    )

    @Test
    fun anUntrustedIncomingTempoIsNotBeatMatchedOrStretched() {
        val current = track("trusted-out", 120.0, beatConfidence = 0.9)
        val next = track("guessed-in", 111.0, beatConfidence = 0.05)

        for (advanced in listOf(false, true)) {
            val plan = plan(current, next, advanced = advanced)
            assertEquals(
                1.0,
                plan.incomingPlaybackRate,
                1e-9,
                "advanced=$advanced stretched an incoming grid the policy calls a guess",
            )
            assertNotEquals(
                TransitionStyle.DJ_BLEND,
                plan.transitionStyle,
                "advanced=$advanced beat-blended an incoming grid the policy calls a guess",
            )
        }
    }

    @Test
    fun phraseModeDoesNotStretchAnUntrustedIncomingTempo() {
        val current = track("trusted-out", 120.0, beatConfidence = 0.9)
        val next = track("guessed-in", 111.0, beatConfidence = 0.05)

        val plan = plan(current, next, advanced = true, phraseBars = PHRASE_BARS_SHORT)
        assertEquals(1.0, plan.incomingPlaybackRate, 1e-9)
        assertNotEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
    }

    @Test
    fun mixOutCandidatesPastTheContentEndAreNeverChosen() {
        val current = track("past-end", 120.0, contentEnd = 100.0).copy(
            mixOutCandidates = listOf(
                MixCandidate(120.0, 1.0, "energy_cliff"),
                MixCandidate(90.0, 0.4, "outro_start"),
            ),
            mixOutTime = 120.0,
        )

        val ranked = rankMixOutCandidates(current, contentEnd = 100.0, duration = 200.0)
        assertTrue(ranked.all { it.time <= 100.0 }, "ranked $ranked")

        val anchor = resolveMixOutAnchor(current, contentEnd = 100.0, duration = 200.0)
        assertTrue(anchor.time <= 100.0, "anchor ${anchor.time} is past the content end")
        assertEquals("content_end", anchor.type, "the out-of-range exit should leave the real content end")
    }

    @Test
    fun anIncomingDropCandidateIsSnappedOntoTheDownbeatGrid() {
        val beat = 60.0 / 120.0
        val next = track("candidate-in", 120.0).let {
            it.copy(
                mixInCandidates = listOf(
                    MixCandidate(it.downbeats[4] + beat * 0.45, 1.0, "main_drop"),
                ),
            )
        }

        val cue = incomingCuePoint(next, latestIncomingCue(next.duration))
        assertTrue(
            next.downbeats.any { kotlin.math.abs(it - cue) < 1e-9 },
            "cue $cue is not on a downbeat",
        )
    }

    @Test
    fun echoRideIsNeverAdvertisedWhileTheRendererKeepsEchoOff() {
        val mask = (0 until 400).map { 0.0 }
        val current = track("instrumental-out", 120.0, vocal = 0.1, contentEnd = 180.0)
            .copy(vocalActivityMask = mask)
        val plans = (0 until 100).map { index ->
            val next = track("echo-$index", 97.0, vocal = 0.1).copy(vocalActivityMask = mask)
            plan(current, next, advanced = true)
        }

        assertTrue(
            plans.none { it.filterVariant == FilterTransitionVariant.ECHO_RIDE },
            "the planner advertised ECHO_RIDE although the renderer keeps echo off",
        )
        assertTrue(plans.none { it.echoSeconds > 0.0 }, "a plan carried an echo time the renderer ignores")
    }
}
