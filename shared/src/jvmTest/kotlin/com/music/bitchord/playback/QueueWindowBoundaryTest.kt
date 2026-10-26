package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueueWindowBoundaryTest {

    private fun snapshot(
        canonicalSize: Int = 2,
        order: List<Int> = (0 until canonicalSize).toList(),
        cursor: Int = -1,
        current: Int? = null,
        shuffle: Boolean = false,
        seed: Long = 0L,
        cycle: Long = 0L,
        consumed: List<Int> = emptyList(),
        lastCycleFinal: Int? = null,
        planned: List<QueueWindowCycleSnapshot> = emptyList(),
    ) = QueueWindowSnapshot(
        canonicalSize = canonicalSize,
        order = order,
        cursor = cursor,
        currentCanonicalIndex = current,
        shuffleEnabled = shuffle,
        shuffleSeed = seed,
        cycleId = cycle,
        consumedThisCycle = consumed,
        lastCycleFinalCanonicalIndex = lastCycleFinal,
        plannedCycles = planned,
    )

    @Test
    fun aNegativeCanonicalSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> { QueueWindow(-1) }
    }

    @Test
    fun aZeroSizeWindowIsUnstartedAndRestorable() {
        val window = QueueWindow(0)
        assertEquals(0, window.canonicalSize)
        assertEquals(-1, window.cursor)
        assertNull(window.current)
        assertEquals(emptyList(), window.upcoming())

        val restored = QueueWindow.restore(window.snapshot())
        assertEquals(window.snapshot(), restored.snapshot())
    }

    @Test
    fun startingOutsideTheCanonicalRangeIsRefused() {
        assertFailsWith<IllegalArgumentException> { QueueWindow(3).startAt(-1) }
        assertFailsWith<IllegalArgumentException> { QueueWindow(3).startAt(3) }
    }

    @Test
    fun aSingleTrackQueueHasNoAutomaticOrExplicitNext() {
        val window = QueueWindow(1).startAt(0)
        assertEquals(emptyList(), window.upcoming())
        assertNull(window.advanceAutomatically())
        assertNull(window.next())
        assertNull(window.previous())

        val wrapped = window.advanceAutomatically(repeatAll = true)!!
        assertEquals(0, wrapped.canonicalIndex)
        assertEquals(1L, wrapped.cycleId)
    }

    @Test
    fun shrinkingAWindowIsRefusedAndKeepingItsSizeIsANoOp() {
        val window = QueueWindow(3)
        assertFailsWith<IllegalArgumentException> { window.extendTo(2) }
        assertSame(window, window.extendTo(3))
    }

    @Test
    fun growingAnUnstartedWindowKeepsCanonicalOrder() {
        val window = QueueWindow(2).extendTo(4)
        assertEquals(listOf(0, 1, 2, 3), window.order)
        assertEquals(-1, window.cursor)
        assertNull(window.current)
    }

    @Test
    fun slicingOutsideTheOrderIsRefusedAndEmptyCountsProduceNothing() {
        val window = QueueWindow(3).startAt(0)
        assertFailsWith<IllegalArgumentException> { window.slice(-1, 1) }
        assertFailsWith<IllegalArgumentException> { window.slice(4, 1) }
        assertEquals(emptyList(), window.slice(1, 0))
        assertEquals(emptyList(), window.slice(1, -2))
        assertEquals(emptyList(), window.slice(3, 2))
    }

    @Test
    fun aRepeatAllSliceCanCrossIntoTheNextCycle() {
        val window = QueueWindow(2).startAt(0)
        val entries = window.slice(2, 2, repeatAll = true)
        assertEquals(listOf(1L, 1L), entries.map { it.cycleId })
        assertEquals(listOf(0, 1), entries.map { it.canonicalIndex })

        val several = window.slice(2, 4, repeatAll = true)
        assertEquals(listOf(1L, 1L, 2L, 2L), several.map { it.cycleId })
    }

    @Test
    fun upcomingBeforeAStartOrWithANonPositiveCountIsEmpty() {
        val window = QueueWindow(3)
        assertEquals(emptyList(), window.upcoming())
        assertEquals(emptyList(), window.upcoming(0))
        assertEquals(emptyList(), window.upcoming(-1))
    }

    @Test
    fun jumpingBeforeAStartIsRefused() {
        assertFailsWith<IllegalStateException> { QueueWindow(3).jumpTo(0) }
        assertFailsWith<IllegalArgumentException> { QueueWindow(3).startAt(0).jumpTo(3) }
    }

    @Test
    fun commitBeforeAStartOrToAnImpossibleEntryIsRefused() {
        assertFalse(QueueWindow(3).commit(QueueWindowEntry(0, 0)))
        val window = QueueWindow(3).startAt(0)
        assertFalse(window.commit(QueueWindowEntry(-1, 0)))
        assertFalse(window.commit(QueueWindowEntry(3, 0)))
        assertFalse(window.commit(QueueWindowEntry(0, 2)))
    }

    @Test
    fun commitCanSeekInsideTheCycle() {
        val window = QueueWindow(3).startAt(0)
        assertTrue(window.commit(QueueWindowEntry(2, 0)))
        assertEquals(2, window.currentCanonicalIndex)
        assertEquals(2, window.cursor)
    }

    @Test
    fun commitCanCrossToAPlannedCycle() {
        val window = QueueWindow(2).startAt(0)
        window.advanceAutomatically()
        val planned = window.slice(window.cursor + 1, 1, repeatAll = true).single()
        assertEquals(1L, planned.cycleId)
        assertTrue(window.commit(planned))
        assertEquals(1L, window.cycleId)
        assertEquals(planned.canonicalIndex, window.currentCanonicalIndex)
    }

    @Test
    fun previousAtTheFirstRowAndNextAtTheLastRowAreNullWithoutRepeat() {
        val window = QueueWindow(3).startAt(0)
        assertNull(window.previous())
        window.jumpTo(2)
        assertNull(window.next())
        assertNull(window.advanceAutomatically())
    }

    @Test
    fun togglingToTheSameShuffleStateReturnsTheSameWindow() {
        val window = QueueWindow(3).startAt(0, shuffle = false)
        assertSame(window, window.setShuffle(false))
    }

    @Test
    fun togglingShuffleBeforeAStartDoesNotThrow() {
        val window = QueueWindow(3).setShuffle(true)
        assertTrue(window.isShuffleEnabled)
        assertEquals(listOf(0, 1, 2), window.order)
        assertEquals(-1, window.cursor)
    }

    @Test
    fun aStartedSnapshotRestoresExactly() {
        val original = QueueWindow(10, shuffleSeed = 77L).startAt(4, shuffle = true)
        original.advanceAutomatically()
        original.jumpTo(8)
        val restored = QueueWindow.restore(original.snapshot())
        assertEquals(original.snapshot(), restored.snapshot())
        assertEquals(original.upcoming(), restored.upcoming())
    }

    @Test
    fun aSnapshotWithOnePlannedCycleRestores() {
        val restored = QueueWindow.restore(
            snapshot(
                canonicalSize = 3,
                cursor = 2,
                current = 2,
                consumed = listOf(0, 1, 2),
                planned = listOf(QueueWindowCycleSnapshot(1L, listOf(0, 1, 2))),
            ),
        )
        assertEquals(1, restored.snapshot().plannedCycles.size)
        assertEquals(listOf(0, 1, 2), restored.snapshot().plannedCycles.single().order)
        assertEquals(listOf(0L, 1L, 1L), restored.slice(2, 3, repeatAll = true).map { it.cycleId })
    }

    @Test
    fun aSnapshotOrderMustBeTheWholePermutation() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(canonicalSize = 2, order = listOf(0)))
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(canonicalSize = 2, order = listOf(0, 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(canonicalSize = 2, order = listOf(0, 2)))
        }
    }

    @Test
    fun aSnapshotCursorAndCurrentItemMustAgree() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cursor = -1, current = 0, consumed = listOf(0)))
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cursor = 0, current = null))
        }
    }

    @Test
    fun aSnapshotCursorMustBeInsideTheOrder() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cursor = 2, current = 0, consumed = listOf(0)))
        }
    }

    @Test
    fun aSnapshotCannotPointItsCursorAtAnotherRow() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(cursor = 0, current = 1, consumed = listOf(0, 1)),
            )
        }
    }

    @Test
    fun snapshotConsumedStateMustBeValid() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cursor = 0, current = 0, consumed = listOf(0, 5)))
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cursor = 0, current = 0, consumed = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cursor = 0, current = 0, consumed = listOf(0, 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(canonicalSize = 2, cursor = -1, current = null, consumed = listOf(0)),
            )
        }
    }

    @Test
    fun aShuffleOffSnapshotMustUseCanonicalOrder() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(order = listOf(1, 0), cursor = 0, current = 1, consumed = listOf(1)),
            )
        }
    }

    @Test
    fun aShuffledSnapshotCannotLeaveAnUnconsumedRowBehindItsCursor() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(
                    shuffle = true,
                    cursor = 1,
                    current = 1,
                    consumed = listOf(1),
                ),
            )
        }
    }

    @Test
    fun aSnapshotCycleMustBeNonNegative() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(snapshot(cycle = -1L))
        }
    }

    @Test
    fun plannedCyclesMustBeConsecutiveAndValidPermutations() {
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(
                    canonicalSize = 3,
                    cursor = 2,
                    current = 2,
                    consumed = listOf(0, 1, 2),
                    planned = listOf(QueueWindowCycleSnapshot(2L, listOf(0, 1, 2))),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(
                    canonicalSize = 3,
                    cursor = 2,
                    current = 2,
                    consumed = listOf(0, 1, 2),
                    planned = listOf(QueueWindowCycleSnapshot(1L, listOf(0, 0, 2))),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QueueWindow.restore(
                snapshot(
                    canonicalSize = 3,
                    cursor = 2,
                    current = 2,
                    consumed = listOf(0, 1, 2),
                    planned = listOf(QueueWindowCycleSnapshot(1L, listOf(1, 0, 2))),
                ),
            )
        }
    }

    @Test
    fun theSizeAliasesAndInversePermutationAgree() {
        val window = QueueWindow(5, shuffleSeed = 3L).startAt(2, shuffle = true)
        assertEquals(5, window.canonicalSize)
        assertEquals(window.canonicalSize, window.canonicalN)
        assertEquals(window.canonicalSize, window.n)
        window.order.forEachIndexed { position, canonical ->
            assertEquals(position, window.posOf[canonical])
        }
    }

    @Test
    fun startingConsumesTheCurrentRow() {
        val window = QueueWindow(4).startAt(2)
        assertEquals(setOf(2), window.consumedThisCycle)
    }
}
