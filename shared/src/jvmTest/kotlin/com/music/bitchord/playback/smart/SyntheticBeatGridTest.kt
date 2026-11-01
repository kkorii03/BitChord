package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the production Beat This! grid helpers, not the DSP analyzer.
 *
 * A synthetic logit train with one decisive peak every 25 frames is exactly a
 * 120 BPM grid at the model's 50 Hz frame rate. The same train supplies the
 * phrase times, because the planner and `phraseAligned` must read the model
 * clock rather than the DSP analyzer's slightly different beat interval.
 */
class SyntheticBeatGridTest {

    private val frameRate = 50.0

    private fun logitsFor120Bpm(frames: Int = 1_500): FloatArray =
        FloatArray(frames) { frame ->
            if (frame % 25 == 0) 3.0f else 0.0f
        }

    @Test
    fun `a 120 BPM logit train produces a trusted production grid`() {
        val logits = logitsFor120Bpm()
        val peaks = BeatTracker.pickPeaks(logits)
        val beats = peaks.map { it / frameRate }
        val bpm = BeatTracker.tempoFromBeats(beats)
        val confidence = BeatTracker.gridConfidence(
            beats,
            peaks.map { frame -> logits[frame.toInt()].toDouble() },
        )

        assertTrue(abs(bpm - 120.0) < 1.0, "detected $bpm BPM from ${peaks.size} peaks")
        assertTrue(confidence >= 0.55, "grid confidence was $confidence")
    }

    @Test
    fun `tail phrase boundaries are phased from the track's first downbeat`() {
        // Head 0-30.5 s and a tail window opening 100.5 s in, both at 120 BPM
        // (one bar every 2 s). The tail's first bar is the third bar of a
        // phrase, not a phrase start, so local indexing would report 100.5 s
        // and 116.5 s instead of the real 112.5 s and 128.5 s.
        val head = (0 until 16).map { 0.5 + it * 2.0 }
        val tail = (0 until 16).map { 100.5 + it * 2.0 }
        val phrases = BeatTracker.phraseBoundaries(head + tail, head.first(), 0.5)

        assertEquals(listOf(0.5, 16.5, 112.5, 128.5), phrases)
    }

    @Test
    fun `phrase boundaries follow the tracked downbeat grid`() {
        // 120 BPM: one bar every 2 s, one eight-bar phrase every 16 s.
        val downbeats = (0 until 24).map { 0.5 + it * 2.0 }
        val phrases = BeatTracker.phraseBoundaries(downbeats)

        assertEquals(listOf(0.5, 16.5, 32.5), phrases)
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "synthetic",
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = downbeats,
            phraseBoundaries = phrases,
        )
        assertTrue(isPhraseBoundary(analysis, phrases[1]), "the derived boundary must read as one")
    }
}
