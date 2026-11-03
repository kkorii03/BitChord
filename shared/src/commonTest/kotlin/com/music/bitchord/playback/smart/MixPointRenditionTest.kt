package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for 11-03: a 2:53 track whose analysis was taken on an earlier
 * source reported its content end at 1:50, and the Automix marker sat there
 * while nothing mixed. Stored analyses are keyed per track, so a mid-song
 * rendition swap must not lend the new file the old cut's timeline.
 */
class MixPointRenditionTest {

    private fun track(
        id: String,
        duration: Double,
        contentEnd: Double = duration - 2,
    ): TrackAnalysis {
        val bpm = 100.0
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = contentEnd,
            mixInTime = downbeats.getOrNull(4) ?: 0.0,
        )
    }

    private fun plan(current: TrackAnalysis, next: TrackAnalysis, length: Double): TransitionPlan =
        planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo(current.trackId, (length * 1000).toLong()),
            nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
            currentTime = 79.0,
            duration = length,
            fadeSeconds = 6.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )

    @Test
    fun `an analysis from a shorter rendition falls back to the end of the playing one`() {
        // The old source measured 1:50 of content; the file now playing is 2:53.
        val stale = track("watermelon", duration = 110.0, contentEnd = 108.0)
        val next = track("next", duration = 200.0)

        val plan = plan(stale, next, length = 173.0)

        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertTrue(
            plan.transitionStart >= 160.0,
            "mix-out must sit in the real outro, not at ${plan.transitionStart}",
        )
    }

    @Test
    fun `a content end outside the tail window falls back to the end`() {
        val regional = track("watermelon", duration = 173.0, contentEnd = 108.0)
        val next = track("next", duration = 200.0)

        val plan = plan(regional, next, length = 173.0)

        assertTrue(
            plan.transitionStart >= 160.0,
            "mix-out must sit in the real outro, not at ${plan.transitionStart}",
        )
    }

    @Test
    fun `an analysis of the playing rendition keeps its planned mix`() {
        val current = track("watermelon", duration = 173.0, contentEnd = 171.0)
        val next = track("next", duration = 200.0)

        val plan = plan(current, next, length = 173.0)

        assertTrue(plan.transitionStart > 150.0, "start=${plan.transitionStart}")
        assertFalse(plan.blocked)
    }

    @Test
    fun `only timing inside the measured tail window fits the playing rendition`() {
        val midSong = track("watermelon", duration = 173.0, contentEnd = 108.0)
        val realOutro = track("watermelon", duration = 173.0, contentEnd = 171.0)
        val oldCut = track("watermelon", duration = 110.0, contentEnd = 108.0)

        assertFalse(AutomixWorkPolicy.analysisFitsPlayback(midSong, 173.0))
        assertTrue(AutomixWorkPolicy.analysisFitsPlayback(realOutro, 173.0))
        assertFalse(AutomixWorkPolicy.analysisFitsPlayback(oldCut, 173.0))
    }
}
