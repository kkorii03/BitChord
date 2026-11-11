package dev.dunkblock.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowTest {
    private fun pick(defence: Boolean, tx: Float, ty: Float, mx: Float, my: Float, src: Int = 1) = MatchupPicker.Pick().also {
        it.defence = defence; it.src = src; it.myX = mx; it.myY = my
        if (src > 0) { it.x = tx; it.y = ty }
    }

    private fun deg(want: Float, got: Float) = assertTrue(abs(want - got) < 0.01f, "want $want, got $got")

    @Test fun pushesTowardsTheMatchupOnTheScreen() {
        deg(0f, FollowRule().step(1.0, pick(true, 700f, 300f, 500f, 300f)))         // matchup to the right
        deg(180f, FollowRule().step(1.0, pick(true, 300f, 300f, 500f, 300f)))       // to the left
        deg(90f, FollowRule().step(1.0, pick(true, 500f, 200f, 500f, 300f)))        // above (further from the camera)
        deg(-90f, FollowRule().step(1.0, pick(true, 500f, 400f, 500f, 300f)))       // below
        // Depth counts 1.6 times: 100 px right and 100 px up is steeper than 45 degrees.
        deg(Math.toDegrees(Math.atan2(160.0, 100.0)).toFloat(), FollowRule().step(1.0, pick(true, 600f, 200f, 500f, 300f)))
    }

    @Test fun nothingOutsideDefenceOrWithoutBothPositions() {
        val f = FollowRule()
        assertTrue(f.step(1.0, pick(false, 700f, 300f, 500f, 300f)).isNaN()); assertEquals("not defence", f.why)
        assertTrue(f.step(1.1, pick(true, 0f, 0f, 500f, 300f, src = 0)).isNaN()); assertEquals("no target", f.why)
        assertTrue(f.step(1.2, pick(true, 700f, 300f, -1f, -1f)).isNaN()); assertEquals("no me", f.why)
    }

    @Test fun startsFarStopsNear() {
        val f = FollowRule()
        assertTrue(f.step(1.0, pick(true, 570f, 300f, 500f, 300f)).isNaN())         // 70 px: between NEAR and FAR, standing
        deg(0f, f.step(1.1, pick(true, 590f, 300f, 500f, 300f)))                     // 90 px: start
        deg(0f, f.step(1.2, pick(true, 570f, 300f, 500f, 300f)))                     // 70 px: keeps going
        assertTrue(f.step(1.3, pick(true, 550f, 300f, 500f, 300f)).isNaN())         // 50 px: arrived
        assertEquals("close", f.why)
        assertTrue(f.step(1.4, pick(true, 570f, 300f, 500f, 300f)).isNaN())         // 70 px again: stays put
    }

    @Test fun holdsBrieflyWhenTheTargetFlickersThenLetsGo() {
        val f = FollowRule()
        deg(0f, f.step(1.0, pick(true, 700f, 300f, 500f, 300f)))
        deg(0f, f.step(1.2, pick(true, 0f, 0f, 500f, 300f, src = 0))); assertEquals("hold", f.why)
        assertTrue(f.step(1.4, pick(true, 0f, 0f, 500f, 300f, src = 0)).isNaN())    // 0.4 s without a target
        // Offence in between forgets the push at once.
        deg(0f, f.step(2.0, pick(true, 700f, 300f, 500f, 300f)))
        assertTrue(f.step(2.1, pick(false, 700f, 300f, 500f, 300f)).isNaN())
        assertTrue(f.step(2.2, pick(true, 0f, 0f, 500f, 300f, src = 0)).isNaN())
    }
}
