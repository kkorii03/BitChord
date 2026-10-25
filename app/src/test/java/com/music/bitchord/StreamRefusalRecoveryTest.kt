package com.music.bitchord

import com.music.bitchord.playback.MidPlayRefusalState
import com.music.bitchord.playback.MidPlayRefusalStep
import com.music.bitchord.playback.StreamRefusalRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRefusalRecoveryTest {

    private fun next(
        code: Int = 403,
        state: MidPlayRefusalState = MidPlayRefusalState(),
        canReresolve: Boolean = true,
        canProxy: Boolean = true,
    ) = StreamRefusalRecovery.next(code, state, canReresolve, canProxy)

    @Test
    fun `a refused range is re-resolved before the dead URL is retried`() {
        assertEquals(MidPlayRefusalStep.RERESOLVE_DIRECT, next())
    }

    @Test
    fun `a failed direct re-resolve falls back to the proxy`() {
        assertEquals(
            MidPlayRefusalStep.RERESOLVE_PROXY,
            next(state = MidPlayRefusalState(directTried = true)),
        )
    }

    @Test
    fun `without a proxy the same URL is retried at most twice`() {
        var state = MidPlayRefusalState(directTried = true, proxyTried = true)
        assertEquals(MidPlayRefusalStep.RETRY_SAME_URL, next(state = state, canProxy = false))
        state = state.copy(sameUrlRetries = 1)
        assertEquals(MidPlayRefusalStep.RETRY_SAME_URL, next(state = state, canProxy = false))
        state = state.copy(sameUrlRetries = StreamRefusalRecovery.MAX_SAME_URL_RETRIES)
        assertEquals(MidPlayRefusalStep.GIVE_UP, next(state = state, canProxy = false))
    }

    @Test
    fun `a refusal never spends more than two same-URL retries`() {
        val state = MidPlayRefusalState(
            directTried = true,
            proxyTried = true,
            sameUrlRetries = StreamRefusalRecovery.MAX_SAME_URL_RETRIES,
        )
        assertEquals(MidPlayRefusalStep.GIVE_UP, next(state = state))
    }

    @Test
    fun `a non-refusal status is not a mid-play refusal`() {
        assertFalse(StreamRefusalRecovery.isRefusal(500))
        assertEquals(MidPlayRefusalStep.GIVE_UP, next(code = 500))
    }

    @Test
    fun `refusal codes are the three googlevideo answers`() {
        assertTrue(StreamRefusalRecovery.isRefusal(403))
        assertTrue(StreamRefusalRecovery.isRefusal(404))
        assertTrue(StreamRefusalRecovery.isRefusal(410))
    }

    @Test
    fun `the backoff grows but stays event-driven`() {
        assertEquals(250L, StreamRefusalRecovery.backoffMs(1))
        assertEquals(1_000L, StreamRefusalRecovery.backoffMs(2))
    }

    @Test
    fun `a recovery round is capped at two`() {
        val key = "test-video"
        StreamRefusalRecovery.clear(key)
        assertTrue(StreamRefusalRecovery.canAttempt(key))
        StreamRefusalRecovery.noteAttempt(key)
        assertTrue(StreamRefusalRecovery.canAttempt(key))
        StreamRefusalRecovery.noteAttempt(key)
        assertFalse(StreamRefusalRecovery.canAttempt(key))
        StreamRefusalRecovery.clear(key)
        assertTrue(StreamRefusalRecovery.canAttempt(key))
    }
}
