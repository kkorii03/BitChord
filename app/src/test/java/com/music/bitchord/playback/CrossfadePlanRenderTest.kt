package com.music.bitchord.playback

import com.music.bitchord.playback.smart.TransitionPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The planner's transition-only loudness match has to survive the trip into
 * the renderer. It did not: the plan was handed to [CrossfadeController.begin]
 * for the mix popup, but the render was built without the plan's gains, so
 * both faders ran at unity through every smart blend.
 */
class CrossfadePlanRenderTest {

    @Test
    fun `a plan's loudness match reaches the renderer`() {
        val plan = TransitionPlan(incomingGain = 0.6, outgoingGain = 0.8)

        val render = CrossfadeController.renderFromPlan(plan, advanced = true)

        assertEquals(0.6, render.incomingGain, 0.0)
        assertEquals(0.8, render.outgoingGain, 0.0)
    }
}
