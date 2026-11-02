package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sync-test mode's longer search deadline (11-02): raised for slow emulators, never shortened. */
class SlowTestDeviceDeadlineTest {
    @Test
    fun aSlowTestDeviceGetsTheLongerDeadline() {
        val previous = LyricsRepository.lookupDeadlineMs
        try {
            LyricsRepository.lookupDeadlineMs = LyricsRepository.LOOKUP_DEADLINE_MS
            LyricsRepository.allowSlowTestDevice()
            assertEquals(30_000L, LyricsRepository.lookupDeadline)
        } finally {
            LyricsRepository.lookupDeadlineMs = previous
        }
    }

    @Test
    fun itNeverShortensALongerDeadline() {
        val previous = LyricsRepository.lookupDeadlineMs
        try {
            LyricsRepository.lookupDeadlineMs = 60_000L
            LyricsRepository.allowSlowTestDevice()
            assertEquals(60_000L, LyricsRepository.lookupDeadline)
        } finally {
            LyricsRepository.lookupDeadlineMs = previous
        }
    }
}
