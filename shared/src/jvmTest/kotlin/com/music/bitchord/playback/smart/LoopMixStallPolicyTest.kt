package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopMixStallPolicyTest {

    @Test
    fun `an incoming player stuck on one position is cut after the short bound`() {
        val watch = LoopMixStallPolicy.start(positionMs = 2269L, nowMs = 0L)

        assertFalse(LoopMixStallPolicy.stalled(watch, LoopMixStallPolicy.STALL_MS - 1))
        assertTrue(LoopMixStallPolicy.stalled(watch, LoopMixStallPolicy.STALL_MS))
    }

    @Test
    fun `a progressing incoming player is never cut however long the mix runs`() {
        var watch = LoopMixStallPolicy.start(positionMs = 0L, nowMs = 0L)
        var now = 0L
        var position = 0L
        repeat(200) {
            now += 250L
            position += 250L
            watch = LoopMixStallPolicy.observe(watch, position, now)
            assertFalse(LoopMixStallPolicy.stalled(watch, now))
        }
    }

    @Test
    fun `progress resets the bound`() {
        var watch = LoopMixStallPolicy.start(positionMs = 100L, nowMs = 0L)
        watch = LoopMixStallPolicy.observe(watch, positionMs = 100L, nowMs = 1_000L)
        assertFalse(LoopMixStallPolicy.stalled(watch, 1_400L))

        watch = LoopMixStallPolicy.observe(watch, positionMs = 1_400L, nowMs = 1_400L)
        assertFalse(LoopMixStallPolicy.stalled(watch, 1_400L + LoopMixStallPolicy.STALL_MS - 1))
        assertTrue(LoopMixStallPolicy.stalled(watch, 1_400L + LoopMixStallPolicy.STALL_MS))
    }
}
