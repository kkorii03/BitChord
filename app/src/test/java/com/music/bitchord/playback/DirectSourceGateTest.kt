package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct Source Stream's first-audio rule: when every addon is resting after
 * repeated failures, waiting the whole direct-source window can only end in
 * "no addon", while YouTube could have started at once.
 */
class DirectSourceGateTest {

    @Test
    fun allAddonsRestingSkipsTheWindow() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(listOf(true, true)))
    }

    @Test
    fun oneAwakeAddonStillDeservesTheWindow() {
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(true, false)))
        assertTrue(DirectSourceGate.directLookupWorthWaiting(listOf(false)))
    }

    @Test
    fun noDirectAddonsCannotProduceAnAnswer() {
        assertFalse(DirectSourceGate.directLookupWorthWaiting(emptyList()))
    }
}
