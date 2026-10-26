package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueShuffleRestoreBoundaryTest {

    private fun restored(
        upcoming: List<String>,
        original: List<String>,
        currentId: String? = null,
    ): List<String> = QueueShuffle.restoreOrder(upcoming, original, currentId).map { upcoming[it] }

    @Test
    fun anEmptyUpcomingQueueRestoresToNothing() {
        assertEquals(emptyList<String>(), restored(emptyList(), listOf("a", "b")))
        assertEquals(emptyList<String>(), restored(emptyList(), emptyList()))
        assertEquals(emptyList<String>(), restored(emptyList(), listOf("a", "b"), currentId = "a"))
    }

    @Test
    fun withNoOldOrderTheUpcomingRowsKeepTheirPlace() {
        assertEquals(listOf("c", "a", "b"), restored(listOf("c", "a", "b"), emptyList()))
    }

    @Test
    fun withoutACurrentIdTheWholeOldOrderIsUsed() {
        assertEquals(
            listOf("b", "c", "d"),
            restored(upcoming = listOf("d", "b", "c"), original = listOf("a", "b", "c", "d")),
        )
    }

    @Test
    fun aCurrentIdThatIsNotInTheOldOrderFallsBackToTheWholeOrder() {
        assertEquals(
            listOf("b", "c", "d"),
            restored(
                upcoming = listOf("d", "b", "c"),
                original = listOf("a", "b", "c", "d"),
                currentId = "missing",
            ),
        )
    }

    @Test
    fun restoringStartsStrictlyAfterTheCurrentRow() {
        assertEquals(
            listOf("b", "c"),
            restored(
                upcoming = listOf("c", "b"),
                original = listOf("a", "b", "c"),
                currentId = "a",
            ),
        )
    }

    @Test
    fun aCurrentRowAtTheEndLeavesEveryUpcomingRowWhereItWas() {
        assertEquals(
            listOf("c", "b"),
            restored(
                upcoming = listOf("c", "b"),
                original = listOf("a", "b", "c"),
                currentId = "c",
            ),
        )
    }

    @Test
    fun rowsBeforeTheCurrentRowAreNotWrappedAround() {
        assertEquals(
            listOf("c", "a"),
            restored(
                upcoming = listOf("a", "c"),
                original = listOf("a", "b", "c"),
                currentId = "b",
            ),
        )
    }

    @Test
    fun aDuplicateCurrentIdUsesItsFirstOccurrence() {
        assertEquals(
            listOf("b", "a", "c", "a"),
            restored(
                upcoming = listOf("a", "b", "a", "c"),
                original = listOf("a", "b", "a", "c"),
                currentId = "a",
            ),
        )
    }

    @Test
    fun duplicateUpcomingRowsAreRestoredInOldOrder() {
        assertEquals(
            listOf("a", "a", "b"),
            restored(
                upcoming = listOf("b", "a", "a"),
                original = listOf("a", "a", "b"),
            ),
        )
    }

    @Test
    fun duplicateRowsInTheOldOrderDoNotInventAnExtraUpcomingRow() {
        assertEquals(
            listOf("a", "b"),
            restored(
                upcoming = listOf("a", "b"),
                original = listOf("a", "a", "b"),
            ),
        )
    }

    @Test
    fun aRowRemovedSinceTheOldOrderIsSkipped() {
        assertEquals(
            listOf("b", "d"),
            restored(
                upcoming = listOf("d", "b"),
                original = listOf("a", "b", "c", "d"),
            ),
        )
    }

    @Test
    fun rowsQueuedAfterTheShuffleTrailAtTheEnd() {
        assertEquals(
            listOf("b", "e", "d"),
            restored(
                upcoming = listOf("e", "b", "d"),
                original = listOf("a", "b"),
            ),
        )
    }

    @Test
    fun aBlankCurrentIdIsJustAnotherKey() {
        assertEquals(
            listOf("b", "a"),
            restored(
                upcoming = listOf("a", "b"),
                original = listOf("", "b"),
                currentId = "",
            ),
        )
    }

    @Test
    fun restoringDoesNotMutateTheInputs() {
        val upcoming = mutableListOf("d", "b", "c")
        val original = mutableListOf("a", "b", "c", "d")
        QueueShuffle.restoreOrder(upcoming, original)
        assertEquals(listOf("d", "b", "c"), upcoming)
        assertEquals(listOf("a", "b", "c", "d"), original)
    }

    @Test
    fun anIdentityShuffleIsRotatedByOne() {
        assertEquals(listOf(1, 0), QueueShuffle.avoidIdentityShuffle(listOf(0, 1), listOf(0, 1)))
        assertEquals(listOf(1, 2, 0), QueueShuffle.avoidIdentityShuffle(listOf(0, 1, 2), listOf(0, 1, 2)))
        assertEquals(
            listOf(1, 2, 3, 0),
            QueueShuffle.avoidIdentityShuffle(listOf(0, 1, 2, 3), listOf(0, 1, 2, 3)),
        )
    }

    @Test
    fun aSectionThatCannotMoveIsLeftAlone() {
        assertEquals(emptyList<Int>(), QueueShuffle.avoidIdentityShuffle(emptyList(), emptyList()))
        assertEquals(listOf(7), QueueShuffle.avoidIdentityShuffle(listOf(7), listOf(7)))
    }

    @Test
    fun aRealShuffleIsKept() {
        assertEquals(
            listOf(2, 0, 1),
            QueueShuffle.avoidIdentityShuffle(listOf(0, 1, 2), listOf(2, 0, 1)),
        )
        assertEquals(
            listOf(1, 0),
            QueueShuffle.avoidIdentityShuffle(listOf(0, 1), listOf(1, 0)),
        )
    }
}
