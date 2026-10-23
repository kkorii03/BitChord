package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The counterpart endpoint's week-long verdict: five empty answers switch it
 * off across restarts, and the deadline is what brings it back for one more
 * five-answer trial.
 */
class CounterpartSwitchTest {

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `the deadline is a week after the verdict`() {
        val now = 1_700_000_000_000L
        assertEquals(now + 7 * day, SongVersionPolicy.counterpartOffUntil(now))
        assertEquals(7 * day, SongVersionPolicy.COUNTERPART_OFF_MS)
    }

    @Test
    fun `the switch stays off until the deadline and is tried again at it`() {
        val now = 1_700_000_000_000L
        val until = SongVersionPolicy.counterpartOffUntil(now)
        assertTrue(SongVersionPolicy.counterpartSuppressed(until, now))
        assertTrue(SongVersionPolicy.counterpartSuppressed(until, until - 1))
        assertFalse(SongVersionPolicy.counterpartSuppressed(until, until))
        assertFalse(SongVersionPolicy.counterpartSuppressed(until, until + 6 * day))
    }

    @Test
    fun `nothing stored means the counterpart is asked`() {
        assertFalse(SongVersionPolicy.counterpartSuppressed(null, 1L))
    }

    @Test
    fun `the verdict is written and read back after a restart`() {
        var stored: Long? = null
        val store = object : CounterpartSwitchStore {
            override fun loadOffUntil(): Long? = stored

            override fun saveOffUntil(until: Long) {
                stored = until
            }
        }
        val now = System.currentTimeMillis()
        CounterpartSwitch.attach(store)
        CounterpartSwitch.suppress(now)
        assertEquals(now + SongVersionPolicy.COUNTERPART_OFF_MS, stored ?: -1L)
        assertTrue(CounterpartSwitch.isSuppressed(now))
        // A fresh process attaches the same store and reads the deadline back.
        CounterpartSwitch.attach(store)
        assertEquals(now + SongVersionPolicy.COUNTERPART_OFF_MS, CounterpartSwitch.offUntil() ?: -1L)
        assertTrue(CounterpartSwitch.isSuppressed(now))
        // Leave an already-expired deadline so nothing later in this JVM is
        // silently suppressed by this test.
        CounterpartSwitch.suppress(0L)
    }
}
