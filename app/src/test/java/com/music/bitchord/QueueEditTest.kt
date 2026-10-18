package com.music.bitchord

import com.music.bitchord.playback.QueueEdit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounds checks in front of the queue drawer's remove/move.
 *
 * The drawer hands back a position from the queue it drew; the session's
 * timeline may have shrunk since, and the player throws on a stale index.
 */
class QueueEditTest {

    @Test
    fun `a remove is allowed only for an index the queue still has`() {
        assertTrue(QueueEdit.canRemove(index = 0, count = 3))
        assertTrue(QueueEdit.canRemove(index = 2, count = 3))
        assertFalse(QueueEdit.canRemove(index = 3, count = 3))
        assertFalse(QueueEdit.canRemove(index = -1, count = 3))
    }

    @Test
    fun `a remove from an empty queue is refused`() {
        assertFalse(QueueEdit.canRemove(index = 0, count = 0))
    }

    @Test
    fun `a move onto itself is not an edit`() {
        assertFalse(QueueEdit.canMove(from = 1, to = 1, count = 4))
    }

    @Test
    fun `a move is refused when either end is stale`() {
        assertFalse(QueueEdit.canMove(from = 4, to = 1, count = 4))
        assertFalse(QueueEdit.canMove(from = 1, to = 4, count = 4))
        assertFalse(QueueEdit.canMove(from = -1, to = 1, count = 4))
        assertTrue(QueueEdit.canMove(from = 0, to = 3, count = 4))
    }
}
