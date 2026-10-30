package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transfer that failed must not be recorded as a host success.
 *
 * [HqHostGate.penalty] ranks the next candidates by successes, failures and
 * throughput, so reporting a refused or truncated open as ok let the host that
 * produced it keep winning the next track. The other half of the rule is that
 * a failure already counted by the path that saw it is not counted twice.
 */
class TransferAccountingTest {

    @Test
    fun `a clean transfer is recorded as a success`() {
        val outcome = TransferAccounting.outcome(failed = false, failureAlreadyNoted = false)
        assertTrue(outcome.recordSuccess)
        assertFalse(outcome.noteFailure)
    }

    @Test
    fun `a failure is never recorded as a success`() {
        val outcome = TransferAccounting.outcome(failed = true, failureAlreadyNoted = false)
        assertFalse(outcome.recordSuccess)
        assertTrue(outcome.noteFailure)
    }

    @Test
    fun `a failure already counted is not counted twice`() {
        val outcome = TransferAccounting.outcome(failed = true, failureAlreadyNoted = true)
        assertFalse(outcome.recordSuccess)
        assertFalse(outcome.noteFailure)
    }

    @Test
    fun `a clean transfer ignores a stale failure mark`() {
        val outcome = TransferAccounting.outcome(failed = false, failureAlreadyNoted = true)
        assertTrue(outcome.recordSuccess)
        assertFalse(outcome.noteFailure)
    }
}
