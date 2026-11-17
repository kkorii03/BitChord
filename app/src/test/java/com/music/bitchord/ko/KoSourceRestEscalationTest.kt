package com.music.bitchord.ko

import com.music.bitchord.data.sources.SourceResolver
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

    /**
     * OPT1 battery-retry-only-when-new: 33 "looking once more" retries in 3.5
     * days, 29 ending "no better copy", re-walking sources that were still
     * resting with their probe turn spent.
     */
    @Test
    fun `a later walk is known to skip a source only while its rest and probe spacing last`() {
        firstRest()
        assertFalse("never probed: the retry may take the turn", KoSourceCooldown.skippedAt(id, t0 + minute))
        assertTrue(KoSourceCooldown.takeRestProbe(id, nowMs = t0))
        assertTrue("probe spent, rest running", KoSourceCooldown.skippedAt(id, t0 + minute))
        assertFalse("the probe turn opens again", KoSourceCooldown.skippedAt(id, t0 + 10 * minute))
        assertFalse("the rest is over", KoSourceCooldown.skippedAt(id, t0 + 31 * minute))
        assertFalse("a healthy source is never skipped", KoSourceCooldown.skippedAt("healthy", t0))
        assertTrue("asking about later does not end the rest", KoSourceCooldown.isResting(id, t0 + 29 * minute))
    }

    @Test
    fun `the inconclusive retry is skipped only when resting sources alone left the walk open`() {
        val resting = SourceResolver.SearchOutcome(verdict = "resting", conclusive = false)
        val skippedAgain: (String, Long) -> Boolean = { _, _ -> true }

        val onlyResting = KoSearchReport().apply {
            record(SourceResolver.SearchOutcome(verdict = "title"), "Qobuz")
            record(resting, "Eljohnzc", restingId = "eljohnzc")
        }
        assertTrue(onlyResting.retryOnlyMeetsRests("vid", t0, skippedAgain))
        assertFalse(
            "a resting source that can be asked by then earns the retry",
            onlyResting.retryOnlyMeetsRests("vid", t0) { _, _ -> false },
        )

        val timedOut = KoSearchReport().apply {
            record(resting, "Eljohnzc", restingId = "eljohnzc")
            record(SourceResolver.SearchOutcome(verdict = "timeout", conclusive = false), "Trial")
        }
        assertFalse("a timeout earns the retry", timedOut.retryOnlyMeetsRests("vid", t0, skippedAgain))

        val endedEarly = KoSearchReport().apply {
            record(resting, "Eljohnzc", restingId = "eljohnzc")
            markEndedEarly()
        }
        assertFalse("sources left running earn the retry", endedEarly.retryOnlyMeetsRests("vid", t0, skippedAgain))

        val conclusive = KoSearchReport().apply { record(SourceResolver.SearchOutcome(verdict = "title"), "Qobuz") }
        assertFalse(conclusive.retryOnlyMeetsRests("vid", t0, skippedAgain))
    }
}
