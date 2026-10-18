package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The short-track floor for Automix analysis: a track under a minute is over
 * before a whole-track decode plus two model passes could describe it, while
 * an unknown duration must keep being asked about until it is known.
 */
class TrackAnalysisDurationTest {

    @Test
    fun `unknown duration is not treated as short`() {
        assertTrue(trackLongEnoughToAnalyse(0.0))
        assertTrue(trackLongEnoughToAnalyse(-1.0))
    }

    @Test
    fun `tracks under a minute are not analysed`() {
        assertFalse(trackLongEnoughToAnalyse(30.0))
        assertFalse(trackLongEnoughToAnalyse(59.999))
    }

    @Test
    fun `exactly a minute is analysed`() {
        assertTrue(trackLongEnoughToAnalyse(60.0))
        assertTrue(trackLongEnoughToAnalyse(60.5))
    }

    @Test
    fun `ordinary song lengths are analysed`() {
        assertTrue(trackLongEnoughToAnalyse(180.0))
        assertTrue(trackLongEnoughToAnalyse(300.0))
    }
}
