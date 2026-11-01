package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffQueuePolicyTest {

    @Test
    fun tailFollowsTheQueueAfterTheIncomingTrackWasArmed() {
        assertEquals(
            listOf("c", "d"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "c", "d"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun aRowRemovedDuringArmingIsNotCarriedIntoTheHandoff() {
        assertEquals(
            listOf("d"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "d"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun rowsMovedDuringArmingKeepTheirEditedOrder() {
        assertEquals(
            listOf("d", "c"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "d", "c"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun aShuffledTailIsTakenAsItStands() {
        assertEquals(
            listOf("e", "d", "c", "f"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "e", "d", "c", "f"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun anAutoplayTailRemovedByRepeatAllDoesNotComeBackAtTheHandoff() {
        assertEquals(
            emptyList(),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun autoplayAppendedDuringArmingIsCarriedAcross() {
        assertEquals(
            listOf("c", "d", "e"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "c", "d", "e"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun anIncomingTrackMovedBeforeTheOutgoingOneIsStillFound() {
        assertEquals(
            listOf("a", "c", "d"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("b", "a", "c", "d"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 1,
            ),
        )
    }

    @Test
    fun theOutgoingPlayerHavingRunOnUsesItsCurrentRowAsTheIncomingOne() {
        assertEquals(
            listOf("c", "d"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "c", "d"),
                incomingKey = "b",
                incomingIndexHint = 2,
                outgoingCurrentIndex = 1,
            ),
        )
    }

    @Test
    fun anIncomingTrackThatIsLastHasNoTail() {
        assertEquals(
            emptyList(),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun aRemovedIncomingTrackHasNoTailToCopy() {
        assertNull(
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "c", "d"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun aRepeatAllWrapTakesTheWholeQueueAsItsTail() {
        assertEquals(
            listOf("b", "c"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("a", "b", "c"),
                incomingKey = "a",
                incomingIndexHint = 0,
                outgoingCurrentIndex = 2,
            ),
        )
    }

    @Test
    fun aStaleArmIndexStillFindsTheIncomingRowAfterHistoryShifted() {
        assertEquals(
            listOf("c"),
            HandoffQueuePolicy.tailAfterIncoming(
                outgoingKeys = listOf("x", "a", "b", "c"),
                incomingKey = "b",
                incomingIndexHint = 1,
                outgoingCurrentIndex = 0,
            ),
        )
    }

    @Test
    fun tailEditsLeaveTheIncomingTrackNext() {
        assertTrue(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "b", "d"),
                outgoingCurrentIndex = 0,
                incomingKey = "b",
                wrapsToStart = false,
            ),
        )
        assertTrue(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "b", "d", "c"),
                outgoingCurrentIndex = 0,
                incomingKey = "b",
                wrapsToStart = false,
            ),
        )
        assertTrue(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "b", "c", "d", "e"),
                outgoingCurrentIndex = 0,
                incomingKey = "b",
                wrapsToStart = false,
            ),
        )
    }

    @Test
    fun aHistoryEditThatShiftsTheRowsStillLeavesTheIncomingTrackNext() {
        assertTrue(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("x", "a", "b", "c"),
                outgoingCurrentIndex = 1,
                incomingKey = "b",
                wrapsToStart = false,
            ),
        )
    }

    @Test
    fun removingOrMovingTheIncomingTrackMakesItStopBeingNext() {
        assertFalse(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "c", "d"),
                outgoingCurrentIndex = 0,
                incomingKey = "b",
                wrapsToStart = false,
            ),
        )
        assertFalse(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "c", "b"),
                outgoingCurrentIndex = 0,
                incomingKey = "b",
                wrapsToStart = false,
            ),
        )
    }

    @Test
    fun repeatAllWrappingToTheFirstRowStaysNext() {
        assertTrue(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "b", "c"),
                outgoingCurrentIndex = 2,
                incomingKey = "a",
                wrapsToStart = true,
            ),
        )
        assertTrue(
            HandoffQueuePolicy.incomingStillNext(
                outgoingKeys = listOf("a", "b", "c", "d"),
                outgoingCurrentIndex = 2,
                incomingKey = "a",
                wrapsToStart = true,
            ),
        )
    }

    @Test
    fun aVersionSwapKeepsTheKeyButChangesTheRecording() {
        assertTrue(
            HandoffQueuePolicy.incomingRecordingChanged(
                outgoingKeys = listOf("a_0", "b_0", "c_0"),
                outgoingCurrentIndex = 0,
                incomingKey = "b_0",
                incomingMediaId = "OLD_VIDEO",
                outgoingMediaIds = listOf("a", "NEW_VIDEO", "c"),
                wrapsToStart = false,
            ),
        )
    }

    @Test
    fun anUnchangedRecordingDoesNotForceARearm() {
        assertFalse(
            HandoffQueuePolicy.incomingRecordingChanged(
                outgoingKeys = listOf("a_0", "b_0", "c_0"),
                outgoingCurrentIndex = 0,
                incomingKey = "b_0",
                incomingMediaId = "SAME",
                outgoingMediaIds = listOf("a", "SAME", "c"),
                wrapsToStart = false,
            ),
        )
    }

    @Test
    fun aTailEditBehindTheIncomingRowIsNotARecordingChange() {
        assertFalse(
            HandoffQueuePolicy.incomingRecordingChanged(
                outgoingKeys = listOf("a_0", "b_0", "d_0"),
                outgoingCurrentIndex = 0,
                incomingKey = "b_0",
                incomingMediaId = "B",
                outgoingMediaIds = listOf("a", "B", "D"),
                wrapsToStart = false,
            ),
        )
        assertFalse(
            HandoffQueuePolicy.incomingRecordingChanged(
                outgoingKeys = listOf("a_0", "c_0", "d_0"),
                outgoingCurrentIndex = 0,
                incomingKey = "b_0",
                incomingMediaId = "B",
                outgoingMediaIds = listOf("a", "C", "D"),
                wrapsToStart = false,
            ),
        )
    }
}
