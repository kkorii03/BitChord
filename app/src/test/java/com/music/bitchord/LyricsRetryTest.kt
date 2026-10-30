package com.music.bitchord

import com.music.bitchord.ui.LyricsRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reopening the lyrics panel retries a transient miss, but never a live lookup. */
class LyricsRetryTest {

    @Test
    fun aCheckedMissWithNoClaimIsRetried() {
        assertTrue(
            LyricsRetry.shouldRetry(
                checked = true,
                hasLyrics = false,
                lookupClaimed = false,
                hasRequest = true,
            ),
        )
    }

    @Test
    fun aLookupStillRunningIsNotRestarted() {
        assertFalse(
            LyricsRetry.shouldRetry(
                checked = true,
                hasLyrics = false,
                lookupClaimed = true,
                hasRequest = true,
            ),
        )
    }

    @Test
    fun aFoundAnswerIsLeftAlone() {
        assertFalse(
            LyricsRetry.shouldRetry(
                checked = true,
                hasLyrics = true,
                lookupClaimed = false,
                hasRequest = true,
            ),
        )
    }

    @Test
    fun nothingLoadedYetHasNoRequestToRepeat() {
        assertFalse(
            LyricsRetry.shouldRetry(
                checked = false,
                hasLyrics = false,
                lookupClaimed = false,
                hasRequest = false,
            ),
        )
    }
}
