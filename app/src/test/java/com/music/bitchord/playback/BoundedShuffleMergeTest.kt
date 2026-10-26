package com.music.bitchord.playback

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The merge behind a first-page shuffle start. The queue window owns the
 * logical order; this pins the invariants the playback service relies on when
 * continuation pages arrive while the listener is already playing.
 */
class BoundedShuffleMergeTest {

    @Test
    fun `nine pages keep the current song and include every index exactly once`() {
        val pageSize = 100
        val pageCount = 9
        val window = QueueWindow(pageSize, shuffleSeed = 42L).startAt(37, shuffle = true)
        val current = window.currentCanonicalIndex

        for (page in 2..pageCount) {
            window.extendTo(page * pageSize, shuffleIntoUpcoming = true)

            assertEquals(current, window.currentCanonicalIndex)
            assertEquals(current, window.order[window.cursor])
            assertEquals(
                "page $page must hold every canonical index once",
                (0 until page * pageSize).toSet(),
                window.order.toSet(),
            )
            assertEquals(window.order.size, window.order.toSet().size)
        }
        assertEquals(pageCount * pageSize, window.order.size)
    }

    @Test
    fun `each page mixes new rows ahead without disturbing rows already waiting`() {
        val window = QueueWindow(100, shuffleSeed = 7L).startAt(20, shuffle = true)
        val oldUpcoming = window.order.drop(window.cursor + 1)

        window.extendTo(200, shuffleIntoUpcoming = true)

        val upcoming = window.order.drop(window.cursor + 1)
        assertEquals(oldUpcoming, upcoming.filter { it in oldUpcoming })
        assertEquals((0 until 200).toSet() - setOf(20), upcoming.toSet())
        assertEquals(upcoming.size, upcoming.toSet().size)
    }

    @Test
    fun `a page repeating rows already queued adds nothing twice`() {
        val remaining = listOf(1, 2, 3)

        val merged = ShuffleAppend.mergeRemaining(
            remaining = remaining,
            added = listOf(2, 3, 4, 4, 5),
            key = { it.toString() },
            random = Random(11),
        )

        assertEquals(listOf(1, 2, 3), merged.filter { it in remaining })
        assertEquals(remaining.size + 2, merged.size)
        assertEquals(merged.size, merged.toSet().size)
    }
}
