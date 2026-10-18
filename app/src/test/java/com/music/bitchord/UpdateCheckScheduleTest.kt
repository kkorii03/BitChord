package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The once-a-day rule behind the launch-time update check.
 *
 * Kept as a pure decision so the part that used to run on every start can be
 * checked without a device, a network or a clock.
 */
class UpdateCheckScheduleTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun neverCheckedIsDue() {
        assertTrue(AppUpdateChecker.checkIsDue(0L, 1_000L))
    }

    @Test
    fun sameDayIsNotDue() {
        val now = 1_700_000_000_000L
        assertFalse(AppUpdateChecker.checkIsDue(now - 23 * 60 * 60 * 1000, now))
    }

    @Test
    fun exactlyADayLaterIsDue() {
        val last = 1_700_000_000_000L
        assertTrue(AppUpdateChecker.checkIsDue(last, last + day))
    }

    @Test
    fun clockGoingBackwardsIsDue() {
        assertTrue(AppUpdateChecker.checkIsDue(1_700_000_000_000L, 1_600_000_000_000L))
    }
}
