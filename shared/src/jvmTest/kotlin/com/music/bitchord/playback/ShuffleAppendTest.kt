package com.music.bitchord.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShuffleAppendTest {

    @Test
    fun mergeKeepsRemainingOrderAndDropsDuplicates() {
        val remaining = listOf("a", "b", "c")
        val added = listOf("c", "d", "a", "e", "d")

        val merged = ShuffleAppend.mergeRemaining(
            remaining = remaining,
            added = added,
            key = { it },
            random = Random(7),
        )

        assertEquals(listOf("a", "b", "c"), merged.filter { it in remaining })
        assertEquals(remaining.size + 2, merged.size)
        assertEquals(merged.size, merged.toSet().size)
        assertTrue(merged.containsAll(listOf("d", "e")))
    }

    @Test
    fun mergeAddsNothingWhenEveryNewRowIsAlreadyQueued() {
        val remaining = listOf("a", "b")

        val merged = ShuffleAppend.mergeRemaining(
            remaining = remaining,
            added = listOf("b", "a"),
            key = { it },
            random = Random(1),
        )

        assertEquals(remaining, merged)
    }

    @Test
    fun mergeIntoEmptyRemainingKeepsEveryFreshRowOnce() {
        val added = listOf("a", "b", "a", "c")

        val merged = ShuffleAppend.mergeRemaining(
            remaining = emptyList<String>(),
            added = added,
            key = { it },
            random = Random(3),
        )

        assertEquals(3, merged.size)
        assertEquals(setOf("a", "b", "c"), merged.toSet())
    }

    @Test
    fun mergeSpreadsFreshRowsThroughTheRemainingPart() {
        val remaining = (0 until 20).toList()
        val added = (20 until 24).toList()

        val merged = ShuffleAppend.mergeRemaining(
            remaining = remaining,
            added = added,
            key = { it.toString() },
            random = ZeroRandom(),
        )

        assertTrue(merged.take(added.size).all { it in added })
        assertEquals(remaining, merged.drop(added.size))
    }

    @Test
    fun mergeWithoutFreshRowsReturnsTheRemainingListUntouched() {
        val remaining = listOf("a", "b")
        assertEquals(
            remaining,
            ShuffleAppend.mergeRemaining(remaining, emptyList(), key = { it }, random = Random(1)),
        )
    }

    @Test
    fun mergeDropsDuplicatesByKeyNotByValue() {
        data class Row(val id: String, val label: String)
        val remaining = listOf(Row("a", "old"))
        val added = listOf(Row("a", "new"), Row("b", "fresh"), Row("b", "again"))

        val merged = ShuffleAppend.mergeRemaining(
            remaining = remaining,
            added = added,
            key = { it.id },
            random = Random(2),
        )

        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals("old", merged.first { it.id == "a" }.label)
        assertEquals("fresh", merged.first { it.id == "b" }.label)
    }

    private class ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
