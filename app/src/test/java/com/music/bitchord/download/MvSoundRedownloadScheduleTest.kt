package com.music.bitchord.download

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
