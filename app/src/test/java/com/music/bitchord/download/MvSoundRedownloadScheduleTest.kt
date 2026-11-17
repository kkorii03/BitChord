package com.music.bitchord.download

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvSoundRedownloadScheduleTest {

    @Test
    fun `the repair is scheduled only with the switch on a window and a suspect`() {
        assertTrue(
            MvSoundRedownload.shouldSchedule(
                enabled = true,
                foreground = true,
                hasSuspects = { true },
            ),
        )
    }

    @Test
    fun `no window means no work is queued`() {
        assertFalse(
            MvSoundRedownload.shouldSchedule(
                enabled = true,
                foreground = false,
                hasSuspects = { true },
            ),
        )
    }

    @Test
    fun `the switch off means no work is queued`() {
        assertFalse(
            MvSoundRedownload.shouldSchedule(
                enabled = false,
                foreground = true,
                hasSuspects = { true },
            ),
        )
    }

    @Test
    fun `a suspect scan is skipped when the app cannot run the repair anyway`() {
        var scanned = false
        assertFalse(
            MvSoundRedownload.shouldSchedule(
                enabled = true,
                foreground = false,
                hasSuspects = {
                    scanned = true
                    true
                },
            ),
        )
        assertFalse(scanned)
    }

    @Test
    fun `the automatic repair waits while the battery is low`() {
        val constraints = mvRepairConstraints(NetworkType.UNMETERED)
        assertTrue(constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    @Test
    fun `the battery rule also applies on any connection`() {
        val constraints = mvRepairConstraints(NetworkType.CONNECTED)
        assertTrue(constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    @Test
    fun `no suspects means no work is queued`() {
        assertFalse(
            MvSoundRedownload.shouldSchedule(
                enabled = true,
                foreground = true,
                hasSuspects = { false },
            ),
        )
    }
}
