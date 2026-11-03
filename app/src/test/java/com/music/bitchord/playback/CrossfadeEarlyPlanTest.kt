package com.music.bitchord.playback

import com.music.bitchord.playback.smart.AutomixWorkPolicy
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.planTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the early smart arm: a tail analysis published in its own
 * regional timeline reports contentEnd ~24 s on a five-minute file. The planner
 * must ignore that timing and fall back to the end of the track, and the arm
 * guard must still refuse the raw early value if one reaches it.
 */
class CrossfadeEarlyPlanTest {

    private fun analysed(contentEnd: Double, duration: Double = 300.0) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "out",
        duration = duration,
        bpm = 120.0,
        beatInterval = 0.5,
        beatConfidence = 0.9,
        contentEndTime = contentEnd,
        audibleStartTime = 0.5,
    )

    @Test
    fun `an unshifted tail content end falls back to the end of the track`() {
        val plan = planTransition(
            analysis = analysed(contentEnd = 24.0),
            nextAnalysis = analysed(contentEnd = 180.0, duration = 200.0).copy(trackId = "in"),
            currentTrack = TransitionTrackInfo("out", 300_000),
            nextTrack = TransitionTrackInfo("in", 200_000),
            currentTime = 12.0,
            duration = 300.0,
            fadeSeconds = 10.746,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertFalse("planner kept a mid-track exit, start=${plan.transitionStart}", plan.blocked)
        assertTrue(
            "planner put the mix at ${plan.transitionStart}, not in the real outro",
            plan.transitionStart >= 280.0,
        )
    }

    @Test
    fun `the logged arm is refused`() {
        assertTrue(AutomixWorkPolicy.smartArmTooEarly(13_000L, 300_000L, 10_746L))
    }

    @Test
    fun `a real end of track plan is allowed`() {
        assertFalse(AutomixWorkPolicy.smartArmTooEarly(280_000L, 300_000L, 10_746L))
    }

    @Test
    fun `a short track is exempt`() {
        assertFalse(AutomixWorkPolicy.smartArmTooEarly(5_000L, 40_000L, 6_000L))
    }
}
