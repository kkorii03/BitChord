package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.pow

/**
 * The pure half of the transition-quality pass: tempo alignment, key
 * compatibility, loudness matching, cue selection and the fields the planner
 * reports to the renderer and the log.
 */
class AutomixQualityTest {

    private fun track(
        id: String,
        bpm: Double,
        key: String = "C major",
        loudnessLufs: Double = 0.0,
        duration: Double = 200.0,
        energy: Double = 1.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        val curve = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, energy) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = key,
            keyConfidence = 0.9,
            loudnessLufs = loudnessLufs,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = downbeats[4],
            energyCurve = curve,
        )
    }

    @Test
    fun tempoAlignmentIsSymmetricInsideSixPercentAndNeverSlowsAFasterTrackFar() {
        assertEquals(120.0 / 124.0, beatAlignmentRate(120.0, 124.0), 1e-3)
        assertEquals(120.0 / 116.0, beatAlignmentRate(120.0, 116.0), 1e-3)
        assertEquals(120.0 / 111.0, beatAlignmentRate(120.0, 111.0), 1e-3, "a slower track may still be sped up")
        assertEquals(1.0, beatAlignmentRate(120.0, 130.0), 1e-6, "a faster track outside the window is left alone")
        assertEquals(1.0, beatAlignmentRate(0.0, 120.0), 1e-6)
        assertTrue(beatMatchable(120.0, 124.0))
        assertTrue(beatMatchable(120.0, 111.0))
        assertFalse(beatMatchable(120.0, 130.0))
    }

    @Test
    fun keyRelationsCoverSameFifthRelativeAndClash() {
        assertEquals(KeyRelation.SAME, keyRelation("C major", "C major"))
        assertEquals(KeyRelation.PARALLEL, keyRelation("C major", "C minor"))
        assertEquals(KeyRelation.RELATIVE, keyRelation("C major", "A minor"))
        assertEquals(KeyRelation.FIFTH, keyRelation("C major", "G major"))
        assertEquals(KeyRelation.FIFTH, keyRelation("C major", "F major"))
        assertEquals(KeyRelation.NEAR, keyRelation("C major", "D major"))
        assertEquals(KeyRelation.CLASH, keyRelation("C major", "F♯ major"))
        assertEquals(KeyRelation.UNKNOWN, keyRelation("", "C major"))
        assertTrue(keysCompatible("C major", "A minor"))
        assertFalse(keysCompatible("C major", "F♯ major"))
        assertFalse(keysCompatible("", "C major"))
    }

    @Test
    fun loudnessMatchTrimsTheLouderSideAndNeverBoosts() {
        val quieterIncoming = loudnessMatch(outgoingLufs = -9.0, incomingLufs = -13.0)
        assertEquals(1.0, quieterIncoming.incomingGain, 1e-6)
        assertEquals(10.0.pow(-4.0 / 20.0), quieterIncoming.outgoingGain, 1e-6)
        assertEquals(4.0, quieterIncoming.gainDb, 1e-6)

        val louderIncoming = loudnessMatch(outgoingLufs = -13.0, incomingLufs = -9.0)
        assertEquals(10.0.pow(-4.0 / 20.0), louderIncoming.incomingGain, 1e-6)
        assertEquals(1.0, louderIncoming.outgoingGain, 1e-6)
        assertEquals(-4.0, louderIncoming.gainDb, 1e-6)

        assertEquals(LoudnessMatch(), loudnessMatch(0.0, -13.0))
        assertEquals(LoudnessMatch(), loudnessMatch(-10.0, -10.2))
    }

    @Test
    fun incomingCueStaysInsideTheSkipCap() {
        val next = track("next", 120.0)
        val latest = latestIncomingCue(next.duration)
        assertEquals(50.0, latest, 1e-6)
        val cue = incomingCuePoint(next, latest)
        assertTrue(cue <= latest, "cue $cue exceeded $latest")
        assertTrue(next.downbeats.any { kotlin.math.abs(it - cue) < 1e-6 }, "cue $cue is not on a downbeat")
    }

    @Test
    fun plannerReportsBeatPhraseKeyAndLoudness() {
        val current = track("a", 120.0, loudnessLufs = -9.0)
        val next = track("b", 120.0, loudnessLufs = -13.0)
        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertTrue(plan.beatAligned, "matched grids should report beatAligned")
        assertEquals(KeyRelation.SAME, plan.keyRelation)
        assertTrue(plan.keyCompatible)
        assertEquals(4.0, plan.loudnessGainDb, 1e-6)
        assertEquals(1.0, plan.incomingGain, 1e-6)
        assertEquals(10.0.pow(-4.0 / 20.0), plan.outgoingGain, 1e-6)
    }

    @Test
    fun characterFollowsMaterialAndText() {
        val calm = track("calm", 90.0)
        assertEquals(TransitionCharacter.CALM, transitionCharacter(calm, calm, null, null, "content_end"))

        val dance = track("dance", 128.0)
        assertEquals(TransitionCharacter.RHYTHMIC, transitionCharacter(dance, dance, null, null, "content_end"))

        val live = track("live", 120.0)
        val liveInfo = TransitionTrackInfo("live", 200_000, title = "Live at Wembley")
        assertEquals(
            TransitionCharacter.GAPLESS,
            transitionCharacter(live, live, liveInfo, null, "content_end"),
        )
    }

    @Test
    fun livePairPlansAShortGaplessHandoff() {
        val current = track("live", 120.0)
        val next = track("next", 120.0)
        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("live", 200_000, title = "Live at Wembley"),
            nextTrack = TransitionTrackInfo("next", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertEquals(TransitionStyle.GAPLESS, plan.transitionStyle)
        assertTrue(plan.fadeSeconds <= 0.12, "gapless should be a handoff, not a blend")
    }

    @Test
    fun fixedPhrasePlanReportsPhraseAndBeatAlignment() {
        val current = track("a", 120.0)
        val next = track("b", 120.0)
        val plan = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
            phraseBars = PHRASE_BARS_SHORT,
        )
        assertTrue(plan.phraseAligned, "an 8-bar plan opens on a phrase boundary")
        assertTrue(plan.beatAligned, "an 8-bar plan on matched tempi is beat-aligned")
    }
}
