package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerMovesTest {

    @Test
    fun `a move the listener made is theirs`() {
        val moves = ListenerMoves()
        moves.note(nowMs = 1_000L)
        assertTrue(moves.consume(automatic = false, nowMs = 1_100L))
    }

    /** Music-only's own skip is a seek too, and nothing noted it. */
    @Test
    fun `a seek nobody asked for is not the listener's`() {
        assertFalse(ListenerMoves().consume(automatic = false, nowMs = 1_000L))
    }

    @Test
    fun `the note answers one transition only`() {
        val moves = ListenerMoves()
        moves.note(nowMs = 1_000L)
        assertTrue(moves.consume(automatic = false, nowMs = 1_100L))
        // The skip the app makes right after it must read as the app's.
        assertFalse(moves.consume(automatic = false, nowMs = 1_200L))
    }

    @Test
    fun `an automatic advance spends the note and is never the listener's`() {
        val moves = ListenerMoves()
        moves.note(nowMs = 1_000L)
        assertFalse(moves.consume(automatic = true, nowMs = 1_100L))
        assertFalse(moves.consume(automatic = false, nowMs = 1_200L))
    }

    @Test
    fun `a move that landed on no transition expires`() {
        val moves = ListenerMoves(windowMs = 5_000L)
        moves.note(nowMs = 1_000L)
        assertFalse(moves.consume(automatic = false, nowMs = 6_001L))
    }
}
