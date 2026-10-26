package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video/foreign-file timing pass runs only when every earlier term says
 * it may. The truth table keeps the old terms (adjustable, measure, measureNow)
 * and the two the pass used to ignore: the AI button taking its answer back,
 * and a substitute stream whose clock is not the one the answer was made for.
 */
class LyricsTimingGuardTest {

    private fun mayRun(
        adjustable: Boolean = true,
        measure: Boolean = true,
        measureNow: Boolean = true,
        cancelAi: Boolean = true,
        substituted: Boolean = false,
    ) = LyricsTiming.videoTimingMayRun(adjustable, measure, measureNow, cancelAi, substituted)

    @Test
    fun `the ordinary pass runs`() {
        assertTrue(mayRun())
    }

    @Test
    fun `each old reason still stops the pass`() {
        assertFalse(mayRun(adjustable = false))
        assertFalse(mayRun(measure = false))
        assertFalse(mayRun(measureNow = false))
    }

    @Test
    fun `undoing the AI answer does not buy it again`() {
        assertFalse(mayRun(cancelAi = false))
    }

    @Test
    fun `a substitute stream is never measured`() {
        assertFalse(mayRun(substituted = true))
    }
}
