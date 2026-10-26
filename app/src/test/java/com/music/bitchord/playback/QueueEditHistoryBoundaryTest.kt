package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditHistoryBoundaryTest {

    @Test
    fun aRemoveNeedsAnIndexThatStillExists() {
        assertTrue(QueueEdit.canRemove(0, 1))
        assertTrue(QueueEdit.canRemove(0, Int.MAX_VALUE))
        assertTrue(QueueEdit.canRemove(Int.MAX_VALUE - 1, Int.MAX_VALUE))
        assertFalse(QueueEdit.canRemove(-1, Int.MAX_VALUE))
        assertFalse(QueueEdit.canRemove(Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun aRemoveFromAnEmptyOrNegativeQueueIsRefused() {
        assertFalse(QueueEdit.canRemove(0, 0))
        assertFalse(QueueEdit.canRemove(0, -1))
        assertFalse(QueueEdit.canRemove(-1, -1))
    }

    @Test
    fun aMoveOntoItselfIsNeverAnEdit() {
        assertFalse(QueueEdit.canMove(0, 0, 1))
        assertFalse(QueueEdit.canMove(99, 99, 4))
        assertFalse(QueueEdit.canMove(-1, -1, 4))
    }

    @Test
    fun aMoveNeedsBothEndsToExist() {
        assertTrue(QueueEdit.canMove(0, 3, 4))
        assertTrue(QueueEdit.canMove(3, 0, 4))
        assertFalse(QueueEdit.canMove(0, 4, 4))
        assertFalse(QueueEdit.canMove(4, 0, 4))
        assertFalse(QueueEdit.canMove(-1, 0, 4))
        assertFalse(QueueEdit.canMove(0, -1, 4))
    }

    @Test
    fun aMoveInAnEmptyQueueIsRefused() {
        assertFalse(QueueEdit.canMove(0, 1, 0))
        assertFalse(QueueEdit.canMove(0, 1, -2))
    }

    @Test
    fun historyTrimmingStartsOnlyPastTheRetainedRows() {
        assertEquals(0, queueHistoryTrimCount(0))
        assertEquals(0, queueHistoryTrimCount(-100))
        assertEquals(0, queueHistoryTrimCount(MAX_QUEUE_HISTORY - 1))
        assertEquals(0, queueHistoryTrimCount(MAX_QUEUE_HISTORY))
        assertEquals(1, queueHistoryTrimCount(MAX_QUEUE_HISTORY + 1))
        assertEquals(MAX_QUEUE_HISTORY, queueHistoryTrimCount(MAX_QUEUE_HISTORY * 2))
        assertEquals(25, MAX_QUEUE_HISTORY)
    }

    @Test
    fun aJumpRemovesOnlyTheRowsStrictlyBetweenCurrentAndTarget() {
        assertNull(skippedByQueueJump(2, 3))
        assertNull(skippedByQueueJump(2, 2))
        assertNull(skippedByQueueJump(2, 1))
        assertEquals(3..3, skippedByQueueJump(2, 4))
        assertEquals(3..6, skippedByQueueJump(2, 7))
        assertEquals(0..0, skippedByQueueJump(-1, 1))
        assertNull(skippedByQueueJump(-1, 0))
    }

    @Test
    fun aJumpRangeDoesNotWrapOrClampTheTarget() {
        assertEquals(4..9, skippedByQueueJump(3, 10))
        assertEquals(0..(Int.MAX_VALUE - 1), skippedByQueueJump(-1, Int.MAX_VALUE))
    }

    @Test
    fun startingAQueueDropsEverythingBeforeTheChosenRow() {
        assertEquals(listOf("c", "d"), queueStartingAt(listOf("a", "b", "c", "d"), 2))
        assertEquals(listOf("a", "b"), queueStartingAt(listOf("a", "b"), 0))
        assertEquals(listOf("b"), queueStartingAt(listOf("a", "b"), 1))
    }

    @Test
    fun startingAQueueWithANegativeIndexKeepsEverything() {
        assertEquals(listOf("a", "b"), queueStartingAt(listOf("a", "b"), -5))
    }

    @Test
    fun startingAQueuePastTheEndClampsToTheLastRow() {
        assertEquals(listOf("b"), queueStartingAt(listOf("a", "b"), 99))
    }

    @Test
    fun startingAnEmptyQueueIsEmpty() {
        assertEquals(emptyList<String>(), queueStartingAt(emptyList<String>(), 0))
        assertEquals(emptyList<String>(), queueStartingAt(emptyList<String>(), 99))
    }

    @Test
    fun anEmptyQueueHasItsAutoplaySectionAtTheTop() {
        assertEquals(0, autoplaySectionStart(emptyList(), 0))
        assertEquals(0, autoplaySectionStart(emptyList(), -1))
        assertEquals(0, autoplaySectionStart(emptyList(), 99))
    }

    @Test
    fun aQueueWithoutAutoplayPutsTheSectionAtItsEnd() {
        assertEquals(3, autoplaySectionStart(listOf(false, false, false), 0))
        assertEquals(3, autoplaySectionStart(listOf(false, false, false), 2))
        assertEquals(3, autoplaySectionStart(listOf(false, false, false), 99))
    }

    @Test
    fun theSectionIsTheFirstAutoplayRowStillToCome() {
        assertEquals(1, autoplaySectionStart(listOf(true, true, true), 0))
        assertEquals(2, autoplaySectionStart(listOf(true, true, true), 1))
        assertEquals(3, autoplaySectionStart(listOf(true, true, true), 2))
    }

    @Test
    fun autoplayRowsAlreadyPlayedAreAboveTheSection() {
        assertEquals(3, autoplaySectionStart(listOf(true, true, false, true), 1))
        assertEquals(4, autoplaySectionStart(listOf(true, true, false, true), 3))
    }

    @Test
    fun aNegativeCurrentIndexLooksFromTheStartOfTheQueue() {
        assertEquals(0, autoplaySectionStart(listOf(true, false), -1))
        assertEquals(1, autoplaySectionStart(listOf(false, true), -1))
    }

    @Test
    fun aHandQueuedRowMidMixStaysAboveTheSection() {
        assertEquals(4, autoplaySectionStart(listOf(true, true, true, false, true, true), 2))
        assertEquals(4, autoplaySectionStart(listOf(true, true, true, false, true, true), 3))
    }
}
