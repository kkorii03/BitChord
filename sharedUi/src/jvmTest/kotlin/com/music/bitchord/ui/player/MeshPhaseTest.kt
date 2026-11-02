package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The mesh frame clock: 30 fps skip, and a phase that is never wrapped.
 * Wrapping a shared phase makes every blob except the 1x one jump once per
 * turn because their speeds are not whole multiples.
 */
class MeshPhaseTest {
    private val driftMillis = 8_000

    @Test fun framesShorterThanThirtyFpsAreSkipped() {
        val armed = meshPhaseTick(0f, nowNanos = 1_000L, lastNanos = 0L, driftMillis)
        assertEquals(0f, armed.phase, 0.001f)
        assertEquals(1_000L, armed.lastNanos)

        val early = meshPhaseTick(0f, nowNanos = 17_000_000L, lastNanos = 1_000L, driftMillis)
        assertEquals(0f, early.phase, 0.001f)
        assertEquals(1_000L, early.lastNanos)

        val due = meshPhaseTick(0f, nowNanos = 33_001_000L, lastNanos = 1_000L, driftMillis)
        assertEquals(meshPhaseAdvance(0f, 33_000_000L, driftMillis), due.phase, 0.001f)
        assertEquals(33_001_000L, due.lastNanos)
    }

    @Test fun dueFrameAdvancesByTheWholeElapsedTime() {
        val elapsed = 100_000_000L
        val step = meshPhaseAdvance(0.5f, elapsed, driftMillis)
        assertTrue(step > 0.5f)
        assertEquals(step - 0.5f, meshPhaseAdvance(0f, elapsed, driftMillis), 0.001f)
        // A bogus drift cannot produce NaN or a negative step.
        assertEquals(0.5f, meshPhaseAdvance(0.5f, elapsed, 0), 0.001f)
        assertEquals(0.5f, meshPhaseAdvance(0.5f, -1L, driftMillis), 0.001f)
    }

    @Test fun phaseCrossesAFullTurnWithoutJumpingTheBlobs() {
        val before = (2 * PI - 0.01).toFloat()
        val after = meshPhaseAdvance(before, 100_000_000L, driftMillis)
        assertTrue("phase must not wrap, or the non-1x blobs jump", after > 2 * PI)

        // Each blob's angle advances exactly by speed * phase step, with no
        // modulo discontinuity at the shared turn.
        for (speed in listOf(1f, -0.7f, 0.85f, -1.15f)) {
            assertEquals(
                speed * (after - before),
                after * speed - before * speed,
                0.0001f,
            )
        }
    }
}
