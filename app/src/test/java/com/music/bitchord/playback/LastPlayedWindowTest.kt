package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart snapshot's bounded window.
 *
 * It is what survives process death: too small and the restored queue cannot
 * skip back a few tracks, too large and every timeline change serializes more
 * of the queue than the restart path needs. Off-by-one here is user-visible as
 * a missing previous track or a lost current row after a cold start.
 */
class LastPlayedWindowTest {

    @Test
    fun `an empty queue has no window`() {
        assertTrue(LastPlayed.window(size = 0, index = 0).isEmpty())
        assertTrue(LastPlayed.window(size = -1, index = 0).isEmpty())
    }

    @Test
    fun `a single track window contains only that track`() {
        assertEquals(0..0, LastPlayed.window(size = 1, index = 0))
    }

    @Test
    fun `at the head the window keeps the current track and fifty ahead`() {
        val range = LastPlayed.window(size = 100, index = 0)

        assertEquals(0, range.first)
        assertEquals(50, range.last)
        assertEquals(51, range.count())
    }

    @Test
    fun `in the middle it keeps twenty five previous and fifty upcoming`() {
        val range = LastPlayed.window(size = 100, index = 30)

        assertEquals(5, range.first)
        assertEquals(80, range.last)
        assertEquals(76, range.count())
    }

    @Test
    fun `at the tail it clamps to the last track`() {
        val range = LastPlayed.window(size = 100, index = 99)

        assertEquals(74, range.first)
        assertEquals(99, range.last)
        assertEquals(26, range.count())
    }

    @Test
    fun `an unset or out of range index is clamped into the queue`() {
        assertEquals(LastPlayed.window(100, 0), LastPlayed.window(100, -1))
        assertEquals(LastPlayed.window(100, 99), LastPlayed.window(100, 100))
        assertEquals(LastPlayed.window(100, 99), LastPlayed.window(100, Int.MAX_VALUE))
    }

    @Test
    fun `a short queue includes every row exactly once`() {
        val range = LastPlayed.window(size = 10, index = 5)

        assertEquals(0, range.first)
        assertEquals(9, range.last)
        assertEquals(10, range.count())
    }
}
