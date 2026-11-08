package com.music.bitchord.playback.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 11-08 round 150a: PEIvcxSO9zQ played 2.5 minutes with no hearing answer while the row before
 * still uploaded its whole song. The playing row's request must never queue behind a row that
 * is no longer playing.
 */
class HearingSlotsTest {

    @Test
    fun thePlayingRowsWaiterGoesFirstAndAGoneRowWaitsWhileThePlayingRowIsInFlight() {
        // Oldest first: a gone row, the next row, the playing row.
        assertEquals(2, HearingPriority.nextIndex(listOf("old", "next", "now"), emptyList(), "now", "next"))
        // The playing row holds a slot: the gone row waits, the next row may go.
        assertEquals(1, HearingPriority.nextIndex(listOf("old", "next"), listOf("now"), "now", "next"))
        assertNull(HearingPriority.nextIndex(listOf("old"), listOf("now"), "now", "next"))
        // Nothing playing ahead of it: first come, first served.
        assertEquals(0, HearingPriority.nextIndex(listOf("old"), listOf("other"), "now", "next"))
        assertEquals(0, HearingPriority.nextIndex(listOf("old", "now"), emptyList(), null, null))
    }

    @Test
    fun aGoneRowGivesWayBeforeTheNextRowAndThePlayingRowNever() {
        val held = listOf(
            HearingPriority.Held("now", 1, mayYield = true),
            HearingPriority.Held("next", 5, mayYield = true),
            HearingPriority.Held("old", 2, mayYield = true),
            HearingPriority.Held("old", 3, mayYield = true),
            HearingPriority.Held("old", 4, mayYield = false),
        )
        // Of the gone row's yieldable holders, the one started last.
        assertEquals(3, HearingPriority.yieldIndex(held, "now", "next"))
        assertEquals(1, HearingPriority.yieldIndex(held.take(2), "now", "next"))
        assertNull(HearingPriority.yieldIndex(held.take(1), "now", "next"))
        assertNull(HearingPriority.yieldIndex(held, null, null))
    }

    @Test
    fun aFinishedRowsUploadGivesItsSlotToThePlayingRowAndRunsAgainAfter() = runTest {
        var playing: String? = "old"
        val slots = HearingSlots(Semaphore(1), { playing }, { null })
        val order = mutableListOf<String>()
        var oldRuns = 0
        var yielded = 0
        val oldGoesOn = CompletableDeferred<Unit>()
        val old = launch {
            slots.withSlot("old", onYield = { yielded++ }) {
                oldRuns++
                order += "old start $oldRuns"
                if (oldRuns == 1) CompletableDeferred<Unit>().await() else oldGoesOn.await()
                order += "old done"
            }
        }
        runCurrent()
        // The row ends while its whole-song upload is still going.
        playing = "now"
        val now = launch {
            slots.withSlot("now") { order += "now" }
        }
        runCurrent()
        oldGoesOn.complete(Unit)
        now.join()
        old.join()
        assertEquals(listOf("old start 1", "now", "old start 2", "old done"), order)
        assertEquals(1, yielded)
        assertEquals(0, slots.held())
    }

    @Test
    fun aGoneRowQueuedFirstStillWaitsForThePlayingRow() = runTest {
        val slots = HearingSlots(Semaphore(1), { "now" }, { null })
        val order = mutableListOf<String>()
        val firstDone = CompletableDeferred<Unit>()
        val first = launch { slots.withSlot("now") { firstDone.await(); order += "now 1" } }
        runCurrent()
        val old = launch { slots.withSlot("old") { order += "old" } }
        runCurrent()
        val second = launch { slots.withSlot("now") { order += "now 2" } }
        runCurrent()
        firstDone.complete(Unit)
        first.join()
        second.join()
        old.join()
        assertEquals(listOf("now 1", "now 2", "old"), order)
        assertEquals(0, slots.held())
    }

    @Test
    fun thePlayingRowNeverTakesItsOwnSlotAndACancelledWaiterGivesNothingAway() = runTest {
        val semaphore = Semaphore(1)
        val slots = HearingSlots(semaphore, { "now" }, { null })
        val release = CompletableDeferred<Unit>()
        var firstFinished = false
        val first = launch { slots.withSlot("now") { release.await(); firstFinished = true } }
        runCurrent()
        val waiting = launch { slots.withSlot("now") { } }
        runCurrent()
        waiting.cancel()
        runCurrent()
        release.complete(Unit)
        first.join()
        assertEquals(true, firstFinished)
        assertEquals(0, slots.held())
        assertEquals(1, semaphore.availablePermits)
    }
}
