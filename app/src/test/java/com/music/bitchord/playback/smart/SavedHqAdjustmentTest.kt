package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedHqAdjustmentTest {

    @Test
    fun `a saved offset becomes the map the lyric clock applies`() {
        val adjustment = LyricsTiming.savedHqAdjustment(offsetMs = 1_200L, rate = 1.0)

        assertNotNull(adjustment)
        assertEquals(1_200L, adjustment!!.map.map(0L))
        assertEquals(1.0, adjustment.rate, 0.0)
    }

    @Test
    fun `a saved rate paces the map`() {
        val adjustment = LyricsTiming.savedHqAdjustment(offsetMs = 0L, rate = 1.01)

        assertNotNull(adjustment)
        assertEquals(1_010L, adjustment!!.map.map(1_000L))
    }

    @Test
    fun `an automatic measurement on a local file already has the file's clock`() {
        val measured = LyricsTiming.savedHqAdjustment(offsetMs = 1_200L, rate = 1.0)

        assertFalse(LyricsTiming.hqApplies(localFile = true, base = measured))
        assertTrue(LyricsTiming.hqApplies(localFile = false, base = measured))
        assertTrue(LyricsTiming.hqApplies(localFile = true, base = null))
        assertFalse(
            LyricsTiming.hqApplies(
                localFile = true,
                base = LyricsTiming.Adjustment.Shift(400L, byAi = true),
            ),
        )
    }

    @Test
    fun `a missing offset is no map and a bad rate falls back to one`() {
        assertNull(LyricsTiming.savedHqAdjustment(offsetMs = null, rate = 1.0))

        val noRate = LyricsTiming.savedHqAdjustment(offsetMs = 500L, rate = Double.NaN)
        assertNotNull(noRate)
        assertEquals(1.0, noRate!!.rate, 0.0)
        assertEquals(500L, noRate.map.map(0L))

        val negative = LyricsTiming.savedHqAdjustment(offsetMs = 500L, rate = -1.0)
        assertNotNull(negative)
        assertEquals(1.0, negative!!.rate, 0.0)
    }
}
