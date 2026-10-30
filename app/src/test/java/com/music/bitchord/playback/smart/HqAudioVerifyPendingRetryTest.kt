package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.playback.smart.HqAudioVerify.NoReferenceReason
import com.music.bitchord.playback.smart.HqAudioVerify.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 429 leaves a song pending for one retry after the host rests. Only an
 * answer that actually settles the question may consume that entry: a measured
 * verdict, a second 429, or a refusal that cannot fix itself. An abandoned
 * play, a host still resting and the transient reference/decode failures the
 * live paths produce must keep the entry, or the one retry is silently spent
 * without the candidate ever being measured.
 */
class HqAudioVerifyPendingRetryTest {

    @Test
    fun `a permanent unavailable answer consumes the pending retry`() {
        assertTrue(with(HqAudioVerify) { Outcome.Unavailable().settlesPendingRetry() })
    }

    @Test
    fun `a transient unavailable answer keeps the pending retry`() {
        assertFalse(
            with(HqAudioVerify) {
                Outcome.Unavailable(transient = true).settlesPendingRetry()
            },
        )
    }

    @Test
    fun `a live transient no-reference keeps the pending retry`() {
        for (reason in listOf(
            NoReferenceReason.HEAD_TIMEOUT,
            NoReferenceReason.DECODE_FAILED,
            NoReferenceReason.CANCELLED,
        )) {
            assertFalse(
                "reason=$reason must not consume the retry",
                with(HqAudioVerify) { Outcome.NoReference(reason).settlesPendingRetry() },
            )
        }
    }

    @Test
    fun `a missing source is permanent and consumes the pending retry`() {
        assertTrue(
            with(HqAudioVerify) {
                Outcome.NoReference(NoReferenceReason.NO_SOURCE).settlesPendingRetry()
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
    fun `a host still resting keeps the pending retry`() {
        assertFalse(with(HqAudioVerify) { Outcome.Deferred.settlesPendingRetry() })
    }

    @Test
    fun `an unexpected failure consumes the pending retry`() {
        assertTrue(with(HqAudioVerify) { (null as Outcome?).settlesPendingRetry() })
    }
}
