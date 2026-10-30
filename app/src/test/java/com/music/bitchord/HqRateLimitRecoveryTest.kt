package com.music.bitchord

import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.playback.HqHostGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A host that keeps refusing gets a longer rest each time, and a success
 * starts the next refusal over at the base rest. The gate is pure arithmetic
 * over an injected clock, so this runs without a server.
 */
class HqRateLimitRecoveryTest {

    @Test
    fun `consecutive refusals double the rest up to ten minutes`() {
        val gate = HqHostGate { 1_000L }

        assertEquals(60_000L, gate.noteRateLimited("tracks.example", null, 429))
        assertEquals(120_000L, gate.noteRateLimited("tracks.example", null, 429))
        assertEquals(240_000L, gate.noteRateLimited("tracks.example", null, 429))
        assertEquals(480_000L, gate.noteRateLimited("tracks.example", null, 429))
        assertEquals(HqRetryPolicy.MAX_REST_MS, gate.noteRateLimited("tracks.example", null, 429))
        assertEquals(HqRetryPolicy.MAX_REST_MS, gate.noteRateLimited("tracks.example", null, 429))
    }

    @Test
    fun `a success resets the next refusal to the base rest`() {
        val gate = HqHostGate { 1_000L }

        gate.noteRateLimited("tracks.example", null, 429)
        assertEquals(120_000L, gate.noteRateLimited("tracks.example", null, 429))

        gate.noteSuccess("tracks.example")

        assertEquals(60_000L, gate.noteRateLimited("tracks.example", null, 429))
    }

    @Test
    fun `a longer server retry-after is not shortened by the escalation cap`() {
        val gate = HqHostGate { 1_000L }

        assertEquals(3_600_000L, gate.noteRateLimited("tracks.example", "3600", 429))
    }
}
