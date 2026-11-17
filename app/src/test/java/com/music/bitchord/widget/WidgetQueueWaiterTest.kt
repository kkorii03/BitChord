package com.music.bitchord.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone 11-17 (vc207, after a cold start): every widget play tap connected to
 * a service whose saved queue had not landed yet, saw an empty player and was
 * dropped; the queue arrived a moment later and nothing played. The command now
 * waits for the queue.
 */
class WidgetQueueWaiterTest {

    @Test
    fun `a command sent before the queue lands runs once it arrives`() {
        var size = 0
        var runs = 0
        val waiter = WidgetQueueWaiter({ size }) { runs++ }

        assertFalse(waiter.runIfQueued())
        assertEquals(0, runs)

        size = 36 // the restored queue
        assertTrue(waiter.runIfQueued())
        assertEquals(1, runs)
    }

    @Test
    fun `a player that already has a queue runs the command at once`() {
        var runs = 0
        val waiter = WidgetQueueWaiter({ 5 }) { runs++ }
        assertTrue(waiter.runIfQueued())
        assertEquals(1, runs)
    }

    @Test
    fun `later timeline changes never send the command again`() {
        var size = 0
        var runs = 0
        val waiter = WidgetQueueWaiter({ size }) { runs++ }
        waiter.runIfQueued()
        size = 3
        waiter.runIfQueued()
        size = 4 // autoplay appends, a queue edit, a second restore step
        assertTrue(waiter.runIfQueued())
        size = 0
        assertTrue(waiter.runIfQueued())
        assertEquals(1, runs)
    }
}
