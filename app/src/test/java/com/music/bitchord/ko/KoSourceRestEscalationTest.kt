package com.music.bitchord.ko

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review G5 (phone log 11-12..11-17): four addons that never answered once
 * were asked again after every 30-minute rest, two failures at a time, for
 * five days. An expired rest used to delete the source's state, so the count
 * started from zero and no rest was ever longer than the first.
 */
class KoSourceRestEscalationTest {

    private val id = "dead-addon"
    private val t0 = 1_000_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() = KoSourceCooldown.resetForTest()

    @After
    fun tearDown() = KoSourceCooldown.resetForTest()

    private fun firstRest() {
        KoSourceCooldown.failed(id, nowMs = t0)
        KoSourceCooldown.failed(id, nowMs = t0)
    }

    @Test
    fun `a source that fails again after its rest rests twice as long`() {
        firstRest()
        assertTrue(KoSourceCooldown.isResting(id, t0 + 29 * minute))
        assertFalse(KoSourceCooldown.isResting(id, t0 + 31 * minute))

        val t1 = t0 + 31 * minute
        KoSourceCooldown.failed(id, nowMs = t1)
        assertTrue("one failure back from a rest rests it again", KoSourceCooldown.isResting(id, t1 + minute))
        assertTrue("the second rest is longer", KoSourceCooldown.isResting(id, t1 + 59 * minute))
        assertFalse(KoSourceCooldown.isResting(id, t1 + 61 * minute))
    }

    @Test
    fun `an answer after the rest starts over`() {
        firstRest()
        val t1 = t0 + 31 * minute
        KoSourceCooldown.answered(id, nowMs = t1)

        KoSourceCooldown.failed(id, nowMs = t1)
        assertFalse("an answered source needs two failures again", KoSourceCooldown.isResting(id, t1 + minute))
        KoSourceCooldown.failed(id, nowMs = t1)
        assertTrue(KoSourceCooldown.isResting(id, t1 + 29 * minute))
        assertFalse("back to the first rest's length", KoSourceCooldown.isResting(id, t1 + 31 * minute))
    }

    @Test
    fun `an answer during a running rest does not end it`() {
        firstRest()
        KoSourceCooldown.answered(id, nowMs = t0 + minute)
        assertTrue(KoSourceCooldown.isResting(id, t0 + 2 * minute))
    }

    @Test
    fun `a success resets the escalation`() {
        firstRest()
        val t1 = t0 + 31 * minute
        KoSourceCooldown.failed(id, nowMs = t1)
        KoSourceCooldown.succeeded(id)

        val t2 = t1 + 2 * 60 * minute
        KoSourceCooldown.failed(id, nowMs = t2)
        KoSourceCooldown.failed(id, nowMs = t2)
        assertTrue(KoSourceCooldown.isResting(id, t2 + 29 * minute))
        assertFalse(KoSourceCooldown.isResting(id, t2 + 31 * minute))
    }

    @Test
    fun `a dead source is probed less often after each rest`() {
        firstRest()
        val t1 = t0 + 31 * minute
        KoSourceCooldown.failed(id, nowMs = t1)

        assertTrue(KoSourceCooldown.takeRestProbe(id, nowMs = t1))
        assertFalse("ten minutes is the first rest's spacing", KoSourceCooldown.takeRestProbe(id, nowMs = t1 + 10 * minute))
        assertTrue(KoSourceCooldown.takeRestProbe(id, nowMs = t1 + 20 * minute))
    }

    @Test
    fun `rest lengths double and are capped`() {
        val cooldown = 30 * minute
        assertEquals(cooldown, KoSourceCooldown.restLengthMs(cooldown, 1))
        assertEquals(2 * cooldown, KoSourceCooldown.restLengthMs(cooldown, 2))
        assertEquals(6 * 60 * minute, KoSourceCooldown.restLengthMs(cooldown, 5))
        assertEquals(6 * 60 * minute, KoSourceCooldown.restLengthMs(cooldown, 40))
        assertEquals("a host's short rest grows from its own length", 4 * minute, KoSourceCooldown.restLengthMs(minute, 3))
        assertEquals("a host asking for longer than the cap is obeyed", 8 * 60 * minute, KoSourceCooldown.restLengthMs(8 * 60 * minute, 3))
    }
}
