package com.music.bitchord.playback.smart

import com.music.bitchord.playback.NextPrepPolicy
import com.music.bitchord.playback.NextPrepPolicy.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-pass cold song check shares one probe between the short opening and
 * the full one. A pass must report only its own engine-stage failure: the
 * short opening's "too few words" is not the answer of a later pass that
 * reached the engine (and would otherwise stop that answer being stored, so
 * the next playback paid for the same song again).
 */
class SongCheckProbeTest {

    @Test
    fun `a later pass drops the previous pass's engine failure`() {
        val probe = LyricsTiming.NextSyncProbe()
        probe.failure = SyncFailure.TOO_FEW_LINES

        probe.beginPass()

        assertNull(probe.failure)
        assertTrue(NextPrepPolicy.shouldStoreSyncResult(probe.failure))
    }

    @Test
    fun `a rate limit set by this pass still blocks storing it`() {
        val probe = LyricsTiming.NextSyncProbe()
        probe.failure = SyncFailure.TOO_FEW_LINES

        probe.beginPass()
        probe.failure = SyncFailure.RATE_LIMIT

        assertEquals(SyncFailure.RATE_LIMIT, probe.failure)
        assertFalse(NextPrepPolicy.shouldStoreSyncResult(probe.failure))
    }
}
