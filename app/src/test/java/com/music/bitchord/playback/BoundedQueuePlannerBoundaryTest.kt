package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedQueuePlannerBoundaryTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        queueTier = tier,
    )

    private fun snapshot(
        songs: List<Song>,
        state: ContinuationState = ContinuationState.EXHAUSTED,
    ) = ContextSnapshot(
        contextToken = "context",
        sourceKey = "source",
        browseId = "browse",
        songs = songs,
        version = 1L,
        continuationToken = null,
        continuationState = state,
    )

    @Test
    fun anEmptyContextCannotProduceAPlan() {
        try {
            BoundedQueuePlanner.buildInitialPlan(
                snapshot(emptyList()),
                selectedIndex = 0,
                queueWindow = QueueWindow(0),
            )
            throw AssertionError("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aSelectionOutsideTheContextCannotProduceAPlan() {
        val context = listOf(song("c0"), song("c1"))
        try {
            BoundedQueuePlanner.buildInitialPlan(
                snapshot(context),
                selectedIndex = -1,
                queueWindow = QueueWindow(2).startAt(0),
            )
            throw AssertionError("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            BoundedQueuePlanner.buildInitialPlan(
                snapshot(context),
                selectedIndex = 2,
                queueWindow = QueueWindow(2).startAt(0),
            )
            throw AssertionError("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aWindowThatDoesNotMatchTheContextCannotProduceAPlan() {
        val context = listOf(song("c0"), song("c1"), song("c2"))
        try {
            BoundedQueuePlanner.buildInitialPlan(
                snapshot(context),
                selectedIndex = 0,
                queueWindow = QueueWindow(2).startAt(0),
            )
            throw AssertionError("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aWindowStartedElsewhereCannotProduceAPlan() {
        val context = listOf(song("c0"), song("c1"), song("c2"))
        try {
            BoundedQueuePlanner.buildInitialPlan(
                snapshot(context),
                selectedIndex = 0,
                queueWindow = QueueWindow(3).startAt(1),
            )
            throw AssertionError("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun aPlanPutsEveryTierInItsOwnSection() {
        val context = listOf(
            song("c0"),
            song("c1", tier = QueueTier.AUTOPLAY),
            song("c2"),
        )
        val plan = BoundedQueuePlanner.buildInitialPlan(
            snapshot(context),
            selectedIndex = 1,
            queueWindow = QueueWindow(3).startAt(1),
            preservedUserQueue = listOf(song("u", tier = QueueTier.CONTEXT)),
            existingAutoplay = listOf(song("a", tier = QueueTier.CONTEXT)),
        )
        assertEquals(listOf("c0", "c1", "u", "c2", "a"), plan.materializedSongs().map { it.videoId })
        assertEquals(
            listOf(
                QueueTier.CONTEXT,
                QueueTier.CONTEXT,
                QueueTier.USER_QUEUE,
                QueueTier.CONTEXT,
                QueueTier.AUTOPLAY,
            ),
            plan.materializedSongs().map { it.queueTier },
        )
    }

    @Test
    fun theContextIsExhaustedOnlyAtATerminalTailWithRepeatAllOff() {
        val context = listOf(song("only"))
        val terminal = BoundedQueuePlanner.buildInitialPlan(
            snapshot(context, ContinuationState.EXHAUSTED),
            selectedIndex = 0,
            queueWindow = QueueWindow(1).startAt(0),
            repeatAll = false,
        )
        assertTrue(terminal.contextTrulyExhausted)

        val blocked = BoundedQueuePlanner.buildInitialPlan(
            snapshot(context, ContinuationState.BLOCKED),
            selectedIndex = 0,
            queueWindow = QueueWindow(1).startAt(0),
            repeatAll = false,
        )
        assertTrue(blocked.contextTrulyExhausted)

        val repeating = BoundedQueuePlanner.buildInitialPlan(
            snapshot(context, ContinuationState.EXHAUSTED),
            selectedIndex = 0,
            queueWindow = QueueWindow(1).startAt(0),
            repeatAll = true,
        )
        assertFalse(repeating.contextTrulyExhausted)

        val pending = BoundedQueuePlanner.buildInitialPlan(
            snapshot(context, ContinuationState.AVAILABLE),
            selectedIndex = 0,
            queueWindow = QueueWindow(1).startAt(0),
            repeatAll = false,
        )
        assertFalse(pending.contextTrulyExhausted)
    }

    @Test
    fun onlyAvailableAndInFlightContinuationsArePending() {
        val context = listOf(song("only"))
        fun pending(state: ContinuationState) = BoundedQueuePlanner.buildInitialPlan(
            snapshot(context, state),
            selectedIndex = 0,
            queueWindow = QueueWindow(1).startAt(0),
        ).continuationPending

        assertTrue(pending(ContinuationState.AVAILABLE))
        assertTrue(pending(ContinuationState.IN_FLIGHT))
        assertFalse(pending(ContinuationState.EXHAUSTED))
        assertFalse(pending(ContinuationState.BLOCKED))
    }

    @Test
    fun topUpStopsAtLowWaterIncludingTheExactBoundary() {
        val window = QueueWindow(80).startAt(0)
        val desired = window.upcoming(WINDOW_AHEAD, repeatAll = false)
        assertTrue(BoundedQueuePlanner.topUpEntries(window, desired.take(LOW_WATER), false).isEmpty())
        assertTrue(BoundedQueuePlanner.topUpEntries(window, desired, false).isEmpty())
    }

    @Test
    fun topUpRejectsAMaterializedPrefixLongerThanTheWindow() {
        val window = QueueWindow(4).startAt(0)
        val desired = window.upcoming(WINDOW_AHEAD, repeatAll = false)
        val tooLong = desired + QueueWindowEntry(99, 0L)
        assertTrue(BoundedQueuePlanner.topUpEntries(window, tooLong, false).isEmpty())
    }

    @Test
    fun topUpFromNothingReturnsTheWholePhysicalWindow() {
        val window = QueueWindow(4).startAt(0)
        assertEquals(
            BoundedQueuePlanner.physicalUpcoming(window, repeatAll = false),
            BoundedQueuePlanner.topUpEntries(window, emptyList(), false),
        )
    }

    @Test
    fun topUpOfAnExactPrefixReturnsOnlyTheSuffix() {
        val window = QueueWindow(80).startAt(0)
        val desired = BoundedQueuePlanner.physicalUpcoming(window, repeatAll = false)
        val existing = desired.take(3)
        assertEquals(desired.drop(3), BoundedQueuePlanner.topUpEntries(window, existing, false))
    }

    @Test
    fun topUpDoesNotMutateItsInputList() {
        val window = QueueWindow(80).startAt(0)
        val existing = mutableListOf<QueueWindowEntry>()
        BoundedQueuePlanner.topUpEntries(window, existing, false)
        assertEquals(emptyList<QueueWindowEntry>(), existing)
    }

    @Test
    fun aNonPositiveLookaheadReturnsNothing() {
        val window = QueueWindow(4).startAt(0)
        assertEquals(emptyList<QueueWindowEntry>(), BoundedQueuePlanner.physicalUpcoming(window, false, 0))
        assertEquals(emptyList<QueueWindowEntry>(), BoundedQueuePlanner.physicalUpcoming(window, false, -1))
    }

    @Test
    fun repeatLookaheadAtTheBoundaryStartsWithTheNextCycle() {
        val window = QueueWindow(3).startAt(2)
        assertEquals(
            listOf(QueueWindowEntry(0, 1L)),
            BoundedQueuePlanner.physicalUpcoming(window, repeatAll = true, count = 1),
        )
        assertTrue(
            BoundedQueuePlanner.physicalUpcoming(window, repeatAll = false, count = 1).isEmpty(),
        )
    }

    @Test
    fun theNextCycleIsMaterializedOnlyAtTheLastActiveEntry() {
        assertFalse(
            BoundedQueuePlanner.shouldMaterializeNextCycle(
                QueueWindow(3).startAt(1),
                repeatAll = true,
            ),
        )
        assertFalse(
            BoundedQueuePlanner.shouldMaterializeNextCycle(
                QueueWindow(3).startAt(2),
                repeatAll = false,
            ),
        )
        assertTrue(
            BoundedQueuePlanner.shouldMaterializeNextCycle(
                QueueWindow(3).startAt(2),
                repeatAll = true,
            ),
        )
    }

    @Test
    fun autoplayNeedsATerminalTailAndRepeatAllOff() {
        val last = QueueWindow(3).startAt(2)
        assertTrue(BoundedQueuePlanner.autoplayEligible(last, ContinuationState.EXHAUSTED, false))
        assertTrue(BoundedQueuePlanner.autoplayEligible(last, ContinuationState.BLOCKED, false))
        assertFalse(BoundedQueuePlanner.autoplayEligible(last, ContinuationState.AVAILABLE, false))
        assertFalse(BoundedQueuePlanner.autoplayEligible(last, ContinuationState.IN_FLIGHT, false))
        assertFalse(BoundedQueuePlanner.autoplayEligible(last, ContinuationState.EXHAUSTED, true))

        val withUpcoming = QueueWindow(3).startAt(0)
        assertFalse(BoundedQueuePlanner.autoplayEligible(withUpcoming, ContinuationState.EXHAUSTED, false))
    }

    @Test
    fun repeatIsAllowedOnlyAfterContinuationIsTerminal() {
        assertFalse(BoundedQueuePlanner.canRepeat(ContinuationState.AVAILABLE))
        assertFalse(BoundedQueuePlanner.canRepeat(ContinuationState.IN_FLIGHT))
        assertTrue(BoundedQueuePlanner.canRepeat(ContinuationState.EXHAUSTED))
        assertTrue(BoundedQueuePlanner.canRepeat(ContinuationState.BLOCKED))
    }

    @Test
    fun materializingSkipsAnUpcomingEntryOutsideTheCanonicalContext() {
        val current = song("c0")
        val plan = BoundedContextPlan(
            canonicalContext = listOf(current),
            currentCanonicalIndex = 0,
            history = emptyList(),
            current = current,
            userQueue = emptyList(),
            contextUpcoming = listOf(
                QueueWindowEntry(0, 0L),
                QueueWindowEntry(5, 0L),
            ),
            autoplay = emptyList(),
            contextTrulyExhausted = false,
            continuationPending = false,
            continuationState = ContinuationState.EXHAUSTED,
            repeatAll = false,
        )
        assertEquals(listOf("c0", "c0"), plan.materializedSongs().map { it.videoId })
    }
}
