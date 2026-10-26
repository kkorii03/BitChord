package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedQueueEditsBoundaryTest {

    @Test
    fun aRequestFromAnOldGenerationChangesNothing() {
        val state = BoundedQueueEditState(contextGeneration = 2L)
        assertEquals(state, BoundedQueueEdits.requestShuffle(state, true, contextGeneration = 1L))
        assertEquals(state, BoundedQueueEdits.requestRefresh(state, contextGeneration = 1L))
        assertEquals(state, BoundedQueueEdits.requestTopUp(state, contextGeneration = 1L))
        assertEquals(state, BoundedQueueEdits.requestShuffle(state, true, contextGeneration = 3L))
    }

    @Test
    fun aRequestFromTheCurrentGenerationIsAccepted() {
        val state = BoundedQueueEditState(contextGeneration = 2L)
        val shuffled = BoundedQueueEdits.requestShuffle(state, false, contextGeneration = 2L)
        assertEquals(false, shuffled.desiredShuffle)

        val refreshed = BoundedQueueEdits.requestRefresh(shuffled, contextGeneration = 2L)
        assertTrue(refreshed.refreshPending)

        val toppedUp = BoundedQueueEdits.requestTopUp(refreshed, contextGeneration = 2L)
        assertTrue(toppedUp.topUpPending)
    }

    @Test
    fun everyPendingFlagIsReported() {
        assertFalse(BoundedQueueEditState().hasPendingEdits)
        assertTrue(BoundedQueueEditState(desiredShuffle = true).hasPendingEdits)
        assertTrue(BoundedQueueEditState(desiredShuffle = false).hasPendingEdits)
        assertTrue(BoundedQueueEditState(refreshPending = true).hasPendingEdits)
        assertTrue(BoundedQueueEditState(topUpPending = true).hasPendingEdits)
    }

    @Test
    fun invalidatingClearsEveryPendingEditAndAdvancesTheToken() {
        val state = BoundedQueueEditState(
            contextGeneration = 7L,
            desiredShuffle = true,
            refreshPending = true,
            topUpPending = true,
        )
        val next = BoundedQueueEdits.invalidateContext(state)
        assertEquals(8L, next.contextGeneration)
        assertFalse(next.hasPendingEdits)
        assertNull(next.desiredShuffle)
        assertFalse(next.refreshPending)
        assertFalse(next.topUpPending)
    }

    @Test
    fun repeatedInvalidationsKeepAdvancing() {
        var state = BoundedQueueEditState()
        state = BoundedQueueEdits.invalidateContext(state)
        state = BoundedQueueEdits.invalidateContext(state)
        state = BoundedQueueEdits.invalidateContext(state)
        assertEquals(3L, state.contextGeneration)
    }

    @Test
    fun theLastContextGenerationCannotBeAdvanced() {
        val exhausted = BoundedQueueEditState(contextGeneration = Long.MAX_VALUE)
        try {
            BoundedQueueEdits.invalidateContext(exhausted)
            throw AssertionError("expected an IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals("context generation exhausted", expected.message)
        }
    }

    @Test
    fun settlingWithNothingPendingDrainsNothing() {
        val state = BoundedQueueEditState(contextGeneration = 4L)
        val settlement = BoundedQueueEdits.settle(state, settledGeneration = 4L)
        assertEquals(state, settlement.state)
        assertNull(settlement.edit)
        assertFalse(settlement.drained)
    }

    @Test
    fun aStaleSettlementLeavesCurrentWorkIntact() {
        val state = BoundedQueueEditState(contextGeneration = 5L, topUpPending = true)
        val settlement = BoundedQueueEdits.settle(state, settledGeneration = 4L)
        assertEquals(state, settlement.state)
        assertNull(settlement.edit)
        assertFalse(settlement.drained)
        assertTrue(settlement.state.hasPendingEdits)
    }

    @Test
    fun aCurrentSettlementDrainsEveryCoalescedEditOnce() {
        val state = BoundedQueueEditState(
            contextGeneration = 3L,
            desiredShuffle = false,
            refreshPending = true,
            topUpPending = true,
        )
        val settlement = BoundedQueueEdits.settle(state, settledGeneration = 3L)
        assertEquals(
            BoundedQueueEdit(
                contextGeneration = 3L,
                desiredShuffle = false,
                refresh = true,
                topUp = true,
            ),
            settlement.edit,
        )
        assertTrue(settlement.drained)
        assertFalse(settlement.state.hasPendingEdits)

        val again = BoundedQueueEdits.settle(settlement.state, settledGeneration = 3L)
        assertNull(again.edit)
        assertFalse(again.drained)
    }

    @Test
    fun deferringPreservesTheExactState() {
        val state = BoundedQueueEditState(
            contextGeneration = 9L,
            desiredShuffle = true,
            refreshPending = true,
            topUpPending = true,
        )
        val settlement = BoundedQueueEdits.defer(state)
        assertEquals(state, settlement.state)
        assertNull(settlement.edit)
        assertFalse(settlement.drained)
    }
}
