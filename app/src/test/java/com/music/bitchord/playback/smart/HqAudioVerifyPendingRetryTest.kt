package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.playback.smart.HqAudioVerify.NoReferenceReason
import com.music.bitchord.playback.smart.HqAudioVerify.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 429 leaves a song pending for one retry after the host rests. The retry
 * must consume that entry for every definitive answer, including a permanent
 * missing reference or an unmeasurable candidate, so later playback events do
 * not download and decode the same song forever. Only an attempt abandoned
 * because the play changed keeps the entry for the next event.
 */
class HqAudioVerifyPendingRetryTest {

    @Test
    fun `unavailable is definitive and consumes the pending retry`() {
        assertTrue(with(HqAudioVerify) { Outcome.Unavailable.settlesPendingRetry() })
    }

    @Test
    fun `no reference is definitive and consumes the pending retry`() {
        assertTrue(
            with(HqAudioVerify) {
                Outcome.NoReference(NoReferenceReason.HEAD_TIMEOUT).settlesPendingRetry()
            },
        )
    }

    @Test
    fun `measured answers consume the pending retry`() {
        assertTrue(with(HqAudioVerify) { Outcome.Mismatch.settlesPendingRetry() })
        assertTrue(
            with(HqAudioVerify) {
                Outcome.Verified(
                    HqVerificationStore.Verified(0L, 1.0, 100, HqTier.NORMAL),
                ).settlesPendingRetry()
            },
        )
    }

    @Test
    fun `an abandoned attempt keeps the pending retry`() {
        assertFalse(with(HqAudioVerify) { Outcome.Abandoned.settlesPendingRetry() })
    }

    @Test
    fun `an unexpected failure consumes the pending retry`() {
        assertTrue(with(HqAudioVerify) { (null as Outcome?).settlesPendingRetry() })
    }
}
