package dev.dunkblock.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockGeometryTest {
    // defender at origin facing +x (right)
    @Test fun frontArcAcceptsFrontRejectsBehind() {
        assertTrue(BlockGeometry.inArc(BlockArc.FRONT_180, 0f, 0f, 0f, 10f, 1f))
        assertTrue(BlockGeometry.inArc(BlockArc.FRONT_180, 0f, 0f, 0f, 0f, 10f))   // exactly side = boundary
        assertFalse(BlockGeometry.inArc(BlockArc.FRONT_180, 0f, 0f, 0f, -10f, 3f)) // behind
    }

    @Test fun sideArcAcceptsSideButNotBack() {
        assertTrue(BlockGeometry.inArc(BlockArc.FRONT_SIDE, 0f, 0f, 0f, -2f, 10f))  // side, slightly back
        assertFalse(BlockGeometry.inArc(BlockArc.FRONT_SIDE, 0f, 0f, 0f, -10f, 0f)) // directly behind
    }

    @Test fun omniAcceptsEverything() {
        assertTrue(BlockGeometry.inArc(BlockArc.OMNI_360, 0f, 0f, 0f, -10f, 0f))
    }

    @Test fun angleWrapsAround() {
        // facing 170 deg, attacker in direction -170 deg (= 190 deg): only 20 deg apart across the wrap
        val ax = (10 * Math.cos(Math.toRadians(-170.0))).toFloat()
        val ay = (10 * Math.sin(Math.toRadians(-170.0))).toFloat()
        assertEquals(20f, BlockGeometry.relativeAngleDeg(0f, 0f, 170f, ax, ay), 0.01f)
        assertTrue(BlockGeometry.inArc(BlockArc.FRONT_180, 0f, 0f, 170f, ax, ay))
    }
}
