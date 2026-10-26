package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueTimelinePolicyTest {

    private fun song(
        id: String,
        tier: QueueTier = QueueTier.CONTEXT,
        entryId: String? = "entry-$id",
        source: String? = null,
        sourceType: PlaybackSourceType? = null,
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        queueTier = tier,
        queueEntryId = entryId,
        playbackSource = source,
        playbackSourceType = sourceType,
    )

    private fun ids(songs: List<Song>) = songs.map { it.videoId }

    @Test
    fun anEmptyContextStillProducesAnEmptyQueue() {
        val result = QueueTimeline.buildContextQueue(
            currentTimeline = listOf(song("now")),
            currentIndex = 0,
            newContextSongs = emptyList(),
            selectedIndex = 0,
            contextSource = QueueSource("Album", PlaybackSourceType.BROWSE, "album"),
        )
        assertEquals(emptyList(), result.timeline)
        assertEquals(0, result.startIndex)
    }

    @Test
    fun aNegativeSelectionClampsToTheFirstContextTrack() {
        val result = QueueTimeline.buildContextQueue(
            currentTimeline = emptyList(),
            currentIndex = -1,
            newContextSongs = listOf(song("c0"), song("c1")),
            selectedIndex = -99,
            contextSource = QueueSource("Album", PlaybackSourceType.BROWSE),
        )
        assertEquals(listOf("c0", "c1"), ids(result.timeline))
        assertEquals(0, result.startIndex)
    }

    @Test
    fun aSelectionPastTheEndClampsToTheLastContextTrack() {
        val result = QueueTimeline.buildContextQueue(
            currentTimeline = emptyList(),
            currentIndex = -1,
            newContextSongs = listOf(song("c0"), song("c1")),
            selectedIndex = 99,
            contextSource = QueueSource("Album", PlaybackSourceType.BROWSE),
        )
        assertEquals(listOf("c0", "c1"), ids(result.timeline))
        assertEquals(1, result.startIndex)
    }

    @Test
    fun aContextQueueKeepsOnlyUpcomingUserRowsBehindTheSelection() {
        val timeline = listOf(
            song("now"),
            song("played", tier = QueueTier.USER_QUEUE),
            song("u1", tier = QueueTier.USER_QUEUE),
            song("a1", tier = QueueTier.AUTOPLAY),
            song("u2", tier = QueueTier.USER_QUEUE),
        )
        val result = QueueTimeline.buildContextQueue(
            currentTimeline = timeline,
            currentIndex = 2,
            newContextSongs = listOf(song("c0"), song("c1"), song("c2")),
            selectedIndex = 1,
            contextSource = QueueSource("Album", PlaybackSourceType.BROWSE),
        )
        assertEquals(listOf("c0", "c1", "u2", "c2"), ids(result.timeline))
        assertEquals(1, result.startIndex)
    }

    @Test
    fun contextRowsTakeTheContextSourceAndTier() {
        val result = QueueTimeline.buildContextQueue(
            currentTimeline = emptyList(),
            currentIndex = 0,
            newContextSongs = listOf(song("c0", tier = QueueTier.AUTOPLAY), song("c1")),
            selectedIndex = 0,
            contextSource = QueueSource("Playlist", PlaybackSourceType.BROWSE, "pl"),
        )
        assertTrue(result.timeline.all { it.queueTier == QueueTier.CONTEXT })
        assertTrue(result.timeline.all { it.playbackSource == "Playlist" })
        assertTrue(result.timeline.all { it.playbackSourceType == PlaybackSourceType.BROWSE })
        assertTrue(result.timeline.all { it.playbackSourceId == "pl" })
    }

    @Test
    fun aOneOffQueueKeepsTheTappedTrackAndUpcomingUserRowsOnly() {
        val timeline = listOf(
            song("now"),
            song("u1", tier = QueueTier.USER_QUEUE),
            song("a1", tier = QueueTier.AUTOPLAY),
            song("u2", tier = QueueTier.USER_QUEUE),
        )
        val result = QueueTimeline.buildOneOffQueue(
            currentTimeline = timeline,
            currentIndex = 0,
            tappedSong = song("tapped", tier = QueueTier.AUTOPLAY),
            source = QueueSource("Search", PlaybackSourceType.SEARCH),
        )
        assertEquals(listOf("tapped", "u1", "u2"), ids(result))
        assertEquals(QueueTier.CONTEXT, result.first().queueTier)
        assertEquals("Search", result.first().playbackSource)
    }

    @Test
    fun aOneOffQueueFromNothingIsJustTheTappedTrack() {
        val result = QueueTimeline.buildOneOffQueue(
            currentTimeline = emptyList(),
            currentIndex = 0,
            tappedSong = song("tapped"),
            source = QueueSource("Home", PlaybackSourceType.HOME),
        )
        assertEquals(listOf("tapped"), ids(result))
    }

    @Test
    fun insertingIntoAnEmptyTimelineUsesIndexZero() {
        assertEquals(0, QueueTimeline.findUserQueueInsertionIndex(emptyList(), 4, isNext = true))
        assertEquals(0, QueueTimeline.findUserQueueInsertionIndex(emptyList(), 4, isNext = false))
    }

    @Test
    fun playNextGoesImmediatelyAfterTheCurrentRow() {
        val timeline = listOf(song("c0"), song("u1", tier = QueueTier.USER_QUEUE), song("c1"))
        assertEquals(1, QueueTimeline.findUserQueueInsertionIndex(timeline, 0, isNext = true))
        assertEquals(2, QueueTimeline.findUserQueueInsertionIndex(timeline, 1, isNext = true))
        assertEquals(3, QueueTimeline.findUserQueueInsertionIndex(timeline, 2, isNext = true))
    }

    @Test
    fun playNextWithNoCurrentRowLandsAtTheHead() {
        val timeline = listOf(song("c0"), song("c1"))
        assertEquals(0, QueueTimeline.findUserQueueInsertionIndex(timeline, -1, isNext = true))
        assertEquals(0, QueueTimeline.findUserQueueInsertionIndex(timeline, -99, isNext = true))
    }

    @Test
    fun addToQueueStopsAtTheFirstNonUserRow() {
        val timeline = listOf(
            song("c0"),
            song("u1", tier = QueueTier.USER_QUEUE),
            song("u2", tier = QueueTier.USER_QUEUE),
            song("a1", tier = QueueTier.AUTOPLAY),
            song("u3", tier = QueueTier.USER_QUEUE),
        )
        assertEquals(3, QueueTimeline.findUserQueueInsertionIndex(timeline, 0, isNext = false))
    }

    @Test
    fun addToQueueBehindARunOfUserRowsReachesTheEnd() {
        val timeline = listOf(
            song("c0"),
            song("u1", tier = QueueTier.USER_QUEUE),
            song("u2", tier = QueueTier.USER_QUEUE),
        )
        assertEquals(3, QueueTimeline.findUserQueueInsertionIndex(timeline, 0, isNext = false))
    }

    @Test
    fun addToQueueWithNoUserRowsGoesAheadOfContext() {
        val timeline = listOf(song("c0"), song("c1"), song("a1", tier = QueueTier.AUTOPLAY))
        assertEquals(1, QueueTimeline.findUserQueueInsertionIndex(timeline, 0, isNext = false))
    }

    @Test
    fun insertionWithAStaleCurrentIndexFallsBackToTheEnd() {
        val timeline = listOf(song("c0"), song("c1"))
        assertEquals(2, QueueTimeline.findUserQueueInsertionIndex(timeline, 99, isNext = true))
        assertEquals(2, QueueTimeline.findUserQueueInsertionIndex(timeline, 99, isNext = false))
    }

    @Test
    fun anInvalidJumpHasNoPlan() {
        val tiers = listOf(QueueTier.CONTEXT, QueueTier.CONTEXT)
        assertNull(QueueTimeline.computeJumpOrder(tiers, -1, 1))
        assertNull(QueueTimeline.computeJumpOrder(tiers, 0, -1))
        assertNull(QueueTimeline.computeJumpOrder(tiers, 0, 2))
        assertNull(QueueTimeline.computeJumpOrder(tiers, 2, 1))
        assertNull(QueueTimeline.computeJumpOrder(emptyList(), 0, 0))
    }

    @Test
    fun backwardAndSameRowJumpsArePureSeeks() {
        val tiers = listOf(QueueTier.CONTEXT, QueueTier.USER_QUEUE, QueueTier.CONTEXT)
        val backward = QueueTimeline.computeJumpOrder(tiers, 2, 0)!!
        assertTrue(backward.isPureSeek)
        assertEquals(listOf(0, 1, 2), backward.newOrderIndices)
        assertEquals(0, backward.newTargetIndex)

        val same = QueueTimeline.computeJumpOrder(tiers, 1, 1)!!
        assertTrue(same.isPureSeek)
        assertEquals(1, same.newTargetIndex)
    }

    @Test
    fun aForwardContextJumpWithoutUserRowsIsAPureSeek() {
        val tiers = listOf(QueueTier.CONTEXT, QueueTier.CONTEXT, QueueTier.CONTEXT)
        val plan = QueueTimeline.computeJumpOrder(tiers, 0, 2)!!
        assertTrue(plan.isPureSeek)
        assertEquals(listOf(0, 1, 2), plan.newOrderIndices)
        assertEquals(2, plan.newTargetIndex)
        assertFalse(plan.promoteTargetToContext)
    }

    @Test
    fun aContextJumpAcrossAUserRowPinsTheUserRowAfterTheTarget() {
        val tiers = listOf(
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
        )
        val plan = QueueTimeline.computeJumpOrder(tiers, 0, 2)!!
        assertFalse(plan.isPureSeek)
        assertEquals(listOf(0, 2, 1), plan.newOrderIndices)
        assertEquals(1, plan.newTargetIndex)
    }

    @Test
    fun aContextJumpKeepsBypassedContextAsHistoryAndAllUserRows() {
        val tiers = listOf(
            QueueTier.CONTEXT,
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.AUTOPLAY,
        )
        val plan = QueueTimeline.computeJumpOrder(tiers, 0, 3)!!
        assertEquals(listOf(0, 1, 3, 2, 4, 5), plan.newOrderIndices)
        assertEquals(2, plan.newTargetIndex)
    }

    @Test
    fun aUserRowJumpDropsBypassedUserRowsButKeepsEverythingElse() {
        val tiers = listOf(
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.USER_QUEUE,
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
            QueueTier.AUTOPLAY,
        )
        val plan = QueueTimeline.computeJumpOrder(tiers, 0, 2)!!
        assertEquals(listOf(0, 2, 3, 4, 5), plan.newOrderIndices)
        assertEquals(1, plan.newTargetIndex)
        assertFalse(plan.promoteTargetToContext)
    }

    @Test
    fun anAutoplayJumpPromotesTheTargetAndDropsTheOldContext() {
        val tiers = listOf(
            QueueTier.CONTEXT,
            QueueTier.CONTEXT,
            QueueTier.AUTOPLAY,
            QueueTier.USER_QUEUE,
            QueueTier.AUTOPLAY,
        )
        val plan = QueueTimeline.computeJumpOrder(tiers, 0, 2)!!
        assertEquals(listOf(0, 2, 3, 4), plan.newOrderIndices)
        assertEquals(1, plan.newTargetIndex)
        assertTrue(plan.promoteTargetToContext)
    }

    @Test
    fun aBackwardJumpNeverProducesAQueueToBuild() {
        val timeline = listOf(song("c0"), song("c1"), song("c2"))
        assertNull(QueueTimeline.buildJumpQueue(timeline, 2, 0))
        assertNull(QueueTimeline.buildJumpQueue(timeline, 1, 1))
        assertNull(QueueTimeline.buildJumpQueue(timeline, -1, 1))
        assertNull(QueueTimeline.buildJumpQueue(timeline, 0, 99))
    }

    @Test
    fun aPureForwardJumpReturnsTheTargetAndEverythingAfterIt() {
        val timeline = listOf(song("c0"), song("c1"), song("c2"))
        val result = QueueTimeline.buildJumpQueue(timeline, 0, 2)!!
        assertEquals(listOf("c2"), ids(result))
        assertEquals("entry-c2", result.single().queueEntryId)
    }

    @Test
    fun anAutoplayJumpPromotionKeepsItsEntryIdAndFillsInMissingSource() {
        val timeline = listOf(
            song("now"),
            song("a1", tier = QueueTier.AUTOPLAY, entryId = "entry-a1"),
        )
        val result = QueueTimeline.buildJumpQueue(timeline, 0, 1)!!
        val promoted = result.first()
        assertEquals("entry-a1", promoted.queueEntryId)
        assertEquals(QueueTier.CONTEXT, promoted.queueTier)
        assertEquals("Title a1", promoted.playbackSource)
        assertEquals(PlaybackSourceType.QUEUE, promoted.playbackSourceType)
    }

    @Test
    fun anAutoplayJumpPromotionKeepsAnExistingSource() {
        val timeline = listOf(
            song("now"),
            song(
                "a1",
                tier = QueueTier.AUTOPLAY,
                source = "Radio Seed",
                sourceType = PlaybackSourceType.EXPLORE,
            ),
        )
        val promoted = QueueTimeline.buildJumpQueue(timeline, 0, 1)!!.first()
        assertEquals("Radio Seed", promoted.playbackSource)
        assertEquals(PlaybackSourceType.EXPLORE, promoted.playbackSourceType)
    }

    @Test
    fun upcomingUserRowsStartAfterTheCurrentRow() {
        val tiers = listOf(
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.AUTOPLAY,
            QueueTier.USER_QUEUE,
        )
        assertEquals(listOf(2, 4), QueueTimeline.upcomingUserQueueIndices(tiers, 1))
        assertEquals(emptyList(), QueueTimeline.upcomingUserQueueIndices(tiers, 4))
        assertEquals(emptyList(), QueueTimeline.upcomingUserQueueIndices(tiers, 99))
    }

    @Test
    fun anUnstartedQueueCountsEveryUserRowAsUpcoming() {
        val tiers = listOf(QueueTier.USER_QUEUE, QueueTier.CONTEXT, QueueTier.USER_QUEUE)
        assertEquals(listOf(0, 2), QueueTimeline.upcomingUserQueueIndices(tiers, -1))
    }

    @Test
    fun playedUserRowsAreOnlyPrunedOnceContextIsPlaying() {
        val tiers = listOf(
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
        )
        assertEquals(listOf(0), QueueTimeline.playedUserQueueIndices(tiers, 1))
        assertEquals(listOf(0, 2), QueueTimeline.playedUserQueueIndices(tiers, 3))
        assertEquals(emptyList(), QueueTimeline.playedUserQueueIndices(tiers, 0))
        assertEquals(emptyList(), QueueTimeline.playedUserQueueIndices(tiers, 2))
        assertEquals(emptyList(), QueueTimeline.playedUserQueueIndices(tiers, -1))
        assertEquals(emptyList(), QueueTimeline.playedUserQueueIndices(tiers, 99))
    }

    @Test
    fun aShuffledStartLeavesAnEmptyQueueAlone() {
        assertEquals(emptyList(), QueueTimeline.shuffledStartingOrder(emptyList(), 0))
    }

    @Test
    fun aShuffledStartPinsTheTappedRowThenTheUserQueueThenAutoplay() {
        val songs = listOf(
            song("c0"),
            song("u1", tier = QueueTier.USER_QUEUE),
            song("c2"),
            song("u3", tier = QueueTier.USER_QUEUE),
            song("a4", tier = QueueTier.AUTOPLAY),
        )
        val order = QueueTimeline.shuffledStartingOrder(songs, startIndex = 2)
        assertEquals("c2", order.first().videoId)
        assertEquals(listOf("u1", "u3"), order.drop(1).take(2).map { it.videoId })
        assertEquals("c0", order[3].videoId)
        assertEquals("a4", order.last().videoId)
    }

    @Test
    fun aShuffledStartClampsAnOutOfRangeIndex() {
        val songs = listOf(song("c0"), song("c1"), song("c2"))
        assertEquals("c0", QueueTimeline.shuffledStartingOrder(songs, -5).first().videoId)
        assertEquals("c2", QueueTimeline.shuffledStartingOrder(songs, 99).first().videoId)
    }

    @Test
    fun aShuffledStartNeverMovesUserRowsAheadOfEachOther() {
        val songs = listOf(
            song("c0"),
            song("u1", tier = QueueTier.USER_QUEUE),
            song("u2", tier = QueueTier.USER_QUEUE),
            song("u3", tier = QueueTier.USER_QUEUE),
            song("c1"),
            song("a0", tier = QueueTier.AUTOPLAY),
        )
        val order = QueueTimeline.shuffledStartingOrder(songs, startIndex = 0)
        assertEquals(listOf("u1", "u2", "u3"), order.drop(1).take(3).map { it.videoId })
    }

    @Test
    fun shuffledUpcomingKeepsUserRowsFirstAndSectionsIntact() {
        val upcoming = listOf(
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.CONTEXT,
            QueueTier.AUTOPLAY,
            QueueTier.USER_QUEUE,
            QueueTier.AUTOPLAY,
        )
        val order = QueueTimeline.shuffledUpcomingOrder(upcoming)
        assertEquals(listOf(1, 4), order.take(2))
        assertEquals(setOf(0, 2), order.slice(2..3).toSet())
        assertEquals(setOf(3, 5), order.slice(4..5).toSet())
    }

    @Test
    fun shuffledUpcomingNeverReturnsAnUnchangedSectionWhenItCanMove() {
        repeat(20) {
            assertEquals(listOf(1, 0), QueueTimeline.shuffledUpcomingOrder(
                listOf(QueueTier.CONTEXT, QueueTier.CONTEXT),
            ))
        }
        repeat(20) {
            assertTrue(
                QueueTimeline.shuffledUpcomingOrder(
                    listOf(QueueTier.CONTEXT, QueueTier.CONTEXT, QueueTier.CONTEXT),
                ) != listOf(0, 1, 2),
            )
        }
    }

    @Test
    fun shuffledUpcomingOfNothingIsNothing() {
        assertEquals(emptyList(), QueueTimeline.shuffledUpcomingOrder(emptyList()))
        assertEquals(listOf(0, 1), QueueTimeline.shuffledUpcomingOrder(
            listOf(QueueTier.USER_QUEUE, QueueTier.USER_QUEUE),
        ))
    }

    @Test
    fun sectionedOrderRegroupsRowsAndKeepsTheirRestoredOrder() {
        val upcoming = listOf(
            QueueTier.CONTEXT,
            QueueTier.USER_QUEUE,
            QueueTier.AUTOPLAY,
            QueueTier.CONTEXT,
            QueueTier.AUTOPLAY,
            QueueTier.USER_QUEUE,
        )
        val restored = listOf(4, 1, 5, 0, 3, 2)
        assertEquals(listOf(1, 5, 0, 3, 4, 2), QueueTimeline.sectionedOrder(restored, upcoming))
    }

    @Test
    fun sectionedOrderDropsRowsThatWereNotRestored() {
        val upcoming = listOf(QueueTier.CONTEXT, QueueTier.USER_QUEUE, QueueTier.AUTOPLAY)
        assertEquals(listOf(1, 0), QueueTimeline.sectionedOrder(listOf(1, 0), upcoming))
        assertEquals(emptyList(), QueueTimeline.sectionedOrder(emptyList(), upcoming))
    }

    @Test
    fun identityShuffleIsRotatedForEveryMovableSize() {
        assertEquals(emptyList(), QueueTimeline.avoidIdentityShuffle(emptyList(), emptyList()))
        assertEquals(listOf(5), QueueTimeline.avoidIdentityShuffle(listOf(5), listOf(5)))
        assertEquals(listOf(1, 0), QueueTimeline.avoidIdentityShuffle(listOf(0, 1), listOf(0, 1)))
        assertEquals(listOf(1, 2, 0), QueueTimeline.avoidIdentityShuffle(listOf(0, 1, 2), listOf(0, 1, 2)))
    }

    @Test
    fun aRealShuffleIsNeverSecondGuessed() {
        assertEquals(
            listOf(2, 0, 1),
            QueueTimeline.avoidIdentityShuffle(listOf(0, 1, 2), listOf(2, 0, 1)),
        )
        assertEquals(
            listOf(1, 2, 0),
            QueueTimeline.avoidIdentityShuffle(listOf(0, 1, 2), listOf(1, 2, 0)),
        )
    }

    @Test
    fun aQueueEntryIdIsAssignedOnce() {
        val first = song("a", entryId = null).asQueueEntry(QueueTier.USER_QUEUE)
        val second = song("a", entryId = null).asQueueEntry(QueueTier.USER_QUEUE)
        assertNotNull(first.queueEntryId)
        assertNotNull(second.queueEntryId)
        assertFalse(first.queueEntryId == second.queueEntryId)
        assertEquals(QueueTier.USER_QUEUE, first.queueTier)

        val kept = first.asQueueEntry(QueueTier.CONTEXT)
        assertEquals(first.queueEntryId, kept.queueEntryId)
        assertEquals(QueueTier.CONTEXT, kept.queueTier)
    }
}
