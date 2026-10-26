package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The provider list: what is offered, in what default order, and how a saved order is brought up to date. */
class LyricsSourceOrderTest {

    @Test
    fun hiddenSourcesAreNeverOffered() {
        assertTrue(LyricsSource.offered.isNotEmpty())
        assertTrue(LyricsSource.offered.none { it.hidden })
        assertTrue(LyricsSource.SIMP_MUSIC !in LyricsSource.offered)
        assertTrue(LyricsSource.MEGALOBIZ !in LyricsSource.offered)
        assertTrue(LyricsSource.GEMINI_TRANSCRIPT !in LyricsSource.offered)
    }

    @Test
    fun offeredKeepsTheDeclarationOrder() {
        assertEquals(LyricsSource.entries.filterNot { it.hidden }, LyricsSource.offered)
    }

    @Test
    fun theAppleHostsLeadAndTheGeneralFallbacksFollow() {
        val order = LyricsSource.offered
        assertTrue(order.indexOf(LyricsSource.LRC_RED) < order.indexOf(LyricsSource.BINI_LYRICS))
        assertTrue(order.indexOf(LyricsSource.BINI_LYRICS) < order.indexOf(LyricsSource.BETTER_LYRICS))
        assertTrue(order.indexOf(LyricsSource.LYRICS_PLUS) < order.indexOf(LyricsSource.VIBE))
        assertTrue(order.indexOf(LyricsSource.VIBE) < order.indexOf(LyricsSource.BUGS))
        assertTrue(order.indexOf(LyricsSource.BUGS) < order.indexOf(LyricsSource.KUGOU))
        assertTrue(order.indexOf(LyricsSource.KUGOU) < order.indexOf(LyricsSource.LRCLIB))
        assertTrue(order.indexOf(LyricsSource.MUSIXMATCH) < order.indexOf(LyricsSource.GENIUS))
        assertEquals(LyricsSource.GENIUS, order.last())
    }

    @Test
    fun anEmptySavedOrderIsTheDefaultOrder() {
        assertEquals(LyricsSource.offered, LyricsSource.ordered(emptyList()))
    }

    @Test
    fun aSavedOrderWithNothingMissingIsKeptVerbatim() {
        val reversed = LyricsSource.offered.reversed()
        assertEquals(reversed, LyricsSource.ordered(reversed))
    }

    @Test
    fun aSavedOrderWithOnlyTheLastSourceGetsEveryNewSourceBeforeIt() {
        assertEquals(LyricsSource.offered, LyricsSource.ordered(listOf(LyricsSource.GENIUS)))
    }

    @Test
    fun hiddenSourcesAreDroppedFromASavedOrder() {
        val saved = listOf(
            LyricsSource.SIMP_MUSIC,
            LyricsSource.GENIUS,
            LyricsSource.MEGALOBIZ,
            LyricsSource.LRC_RED,
        )
        val order = LyricsSource.ordered(saved)
        assertTrue(order.none { it.hidden })
        assertEquals(LyricsSource.GENIUS, order.first())
        assertEquals(LyricsSource.LRC_RED, order[1])
        assertEquals(LyricsSource.offered.filterNot { it == LyricsSource.GENIUS || it == LyricsSource.LRC_RED }, order.drop(2))
    }

    @Test
    fun duplicatesAreRemoved() {
        val order = LyricsSource.ordered(
            listOf(LyricsSource.LRC_RED, LyricsSource.LRC_RED, LyricsSource.BINI_LYRICS),
        )
        assertEquals(LyricsSource.offered, order)
        assertEquals(order.distinct(), order)
    }

    @Test
    fun aSavedRelativeOrderBetweenTwoSourcesIsKept() {
        val order = LyricsSource.ordered(listOf(LyricsSource.GENIUS, LyricsSource.KUGOU))
        assertTrue(order.indexOf(LyricsSource.GENIUS) < order.indexOf(LyricsSource.KUGOU))
        assertTrue(LyricsSource.LRC_RED in order)
    }

    @Test
    fun everyMissingSourceIsSlottedBackAtItsDefaultNeighbour() {
        LyricsSource.offered.forEach { removed ->
            val saved = LyricsSource.offered.filterNot { it == removed }
            val order = LyricsSource.ordered(saved)
            assertEquals(LyricsSource.offered, order, "removed $removed")
            assertEquals(LyricsSource.offered.size, order.size)
        }
    }

    @Test
    fun everyOfferedSourceAppearsExactlyOnceInAnySavedOrder() {
        val saved = listOf(
            LyricsSource.GENIUS,
            LyricsSource.SIMP_MUSIC,
            LyricsSource.LRC_RED,
            LyricsSource.GENIUS,
            LyricsSource.MEGALOBIZ,
        )
        val order = LyricsSource.ordered(saved)
        assertEquals(LyricsSource.offered.size, order.size)
        assertEquals(LyricsSource.offered.toSet(), order.toSet())
        assertFalse(order.zipWithNext().any { (a, b) -> a == b })
    }
}
