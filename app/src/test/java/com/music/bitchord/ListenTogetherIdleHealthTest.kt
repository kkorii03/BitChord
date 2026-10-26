package com.music.bitchord

import com.music.bitchord.data.listentogether.ListenTogether
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherIdleHealthTest {
    @Test
    fun `screen closed never probes`() {
        assertTrue(ListenTogether.shouldSkipIdleProbe(false, false, false, null))
        assertTrue(ListenTogether.shouldSkipIdleProbe(false, false, true, false))
        assertTrue(ListenTogether.shouldSkipIdleProbe(false, true, true, false))
    }

    @Test
    fun `live socket never probes idle server`() {
        assertTrue(ListenTogether.shouldSkipIdleProbe(true, true, true, false))
        assertTrue(ListenTogether.shouldSkipIdleProbe(true, true, false, null))
    }

    @Test
    fun `metered with no party skips`() {
        assertTrue(ListenTogether.shouldSkipIdleProbe(true, false, false, true))
    }

    @Test
    fun `open screen probes on wifi or unknown network`() {
        assertFalse(ListenTogether.shouldSkipIdleProbe(true, false, false, false))
        assertFalse(ListenTogether.shouldSkipIdleProbe(true, false, false, null))
    }

    @Test
    fun `in party probes even on metered when socket down`() {
        assertFalse(ListenTogether.shouldSkipIdleProbe(true, false, true, true))
    }

    @Test
    fun `a health check failure is logged once per day`() {
        assertTrue(ListenTogether.healthFailureLogAllowed(day = 20_000L, lastLoggedDay = -1L))
        assertFalse(ListenTogether.healthFailureLogAllowed(day = 20_000L, lastLoggedDay = 20_000L))
        assertTrue(ListenTogether.healthFailureLogAllowed(day = 20_001L, lastLoggedDay = 20_000L))
    }

    @Test
    fun `a backwards clock does not re-enable the failure log`() {
        assertFalse(ListenTogether.healthFailureLogAllowed(day = 19_999L, lastLoggedDay = 20_000L))
    }
}
