package com.music.bitchord.data.taste

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class TasteSignalsDecayTest {

    private val at = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a counter halves after sixty days`() {
        assertEquals(4.0, TasteDecay.decay(8.0, at, at + TasteDecay.HALF_LIFE_MS), 1e-9)
    }

    @Test
    fun `thirty days leaves the square root of a half`() {
        assertEquals(8.0 * sqrt(0.5), TasteDecay.decay(8.0, at, at + 30 * day), 1e-9)
    }

    @Test
    fun `a fresh counter is unchanged`() {
        assertEquals(3.0, TasteDecay.decay(3.0, at, at), 0.0)
    }

    @Test
    fun `a timestamp in the future is not decayed`() {
        assertEquals(3.0, TasteDecay.decay(3.0, at, at - day), 0.0)
    }

    @Test
    fun `adding brings the old value forward first`() {
        val counter = DecayedValue(value = 4.0, at = at)
        counter.add(1.0, at + TasteDecay.HALF_LIFE_MS)
        assertEquals(3.0, counter.value, 1e-9)
        assertEquals(at + TasteDecay.HALF_LIFE_MS, counter.at)
    }
}
