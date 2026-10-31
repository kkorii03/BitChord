package com.music.bitchord.playback

import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** The next-prep summary reports the latest lossless answer, not the stale null it saw first. */
class NextPrepHqLabelTest {

    @Test
    fun aLaterReadAheadAnswerReplacesTheStaleNoAnswer() {
        assertEquals(
            "lossless_16",
            nextPrepHqLabel(
                prepared = null,
                latest = HqTier.LOSSLESS_16,
                deadlineHit = false,
                skipReason = "no-answer",
            ),
        )
    }

    @Test
    fun thePreparedAnswerIsKeptWhenNothingNewerExists() {
        assertEquals(
            "hi_res_24_44",
            nextPrepHqLabel(
                prepared = HqTier.HI_RES_24_44,
                latest = null,
                deadlineHit = false,
                skipReason = "no-answer",
            ),
        )
    }

    @Test
    fun theOldSkipReasonAndDeadlineAreUnchangedWithoutAnAnswer() {
        assertEquals(
            "none(no-answer)",
            nextPrepHqLabel(null, null, deadlineHit = false, skipReason = "no-answer"),
        )
        assertEquals(
            "none(deadline)",
            nextPrepHqLabel(null, null, deadlineHit = true, skipReason = "no-answer"),
        )
    }
}
