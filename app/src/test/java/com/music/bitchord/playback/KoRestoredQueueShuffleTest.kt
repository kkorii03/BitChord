package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * shuf500: a shuffled 554-row playlist kept restarting with the same songs.
 * The song version controller had put the audio version into an anchored
 * CONTEXT row, the persisted state's anchor check refused every later save,
 * and each restore brought back the snapshot from the moment of the shuffle.
 */
class KoRestoredQueueShuffleTest {

    @get:Rule
    val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    private val source = QueueSource("Mix", PlaybackSourceType.BROWSE, "VLmix")

    @Test
    fun `a version-swapped current row is saved and restored with the shuffle`() {
        val canonical = List(8) { mv(it) }
        val window = QueueWindow(8, shuffleSeed = 77L).startAt(2, shuffle = true)
        val history = window.snapshot()
        val previous = requireNotNull(window.advanceAutomatically(repeatAll = false))
        val logical = window.upcoming(25)
        val swapped = Song(
            videoId = "audio-${previous.canonicalIndex}",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = "https://img/audio",
            durationText = "3:00",
        ).copy(queueTier = QueueTier.CONTEXT, queueEntryId = "current")
        val state = state(
            canonical,
            window.snapshot(),
            entries = listOf(
                anchored(canonical, history.currentCanonicalIndex!!, "history"),
                BoundedContextPersistence.Entry(
                    song = swapped,
                    canonicalIndex = previous.canonicalIndex,
                    cycleId = previous.cycleId,
                    versionOf = canonical[previous.canonicalIndex].contextEntryKey(),
                ),
            ),
            currentIndex = 1,
        )

        val raw = requireNotNull(BoundedContextPersistence.encode(state)) {
            "a swapped row must not make the snapshot unsaveable"
        }
        val restored = requireNotNull(BoundedContextPersistence.decode(raw))
        assertEquals(state.entries, restored.entries)

        val projected = requireNotNull(BoundedContextPersistence.restoreProjection(restored))
        assertEquals("audio-${previous.canonicalIndex}", projected.entries[projected.currentIndex].song.videoId)
        assertEquals(
            logical.map { it.canonicalIndex },
            projected.entries.drop(projected.currentIndex + 1).map { it.canonicalIndex },
        )
    }

    @Test
    fun `a stranger at an anchor is still refused`() {
        val canonical = List(4) { mv(it) }
        val window = QueueWindow(4).startAt(1)
        val stranger = mv(9).copy(queueEntryId = "current")
        val unmarked = state(
            canonical,
            window.snapshot(),
            entries = listOf(BoundedContextPersistence.Entry(stranger, 1, 0L)),
            currentIndex = 0,
        )
        assertNull(BoundedContextPersistence.encode(unmarked))
        // A version mark naming another canonical row does not open the gate.
        val misnamed = unmarked.copy(
            entries = listOf(
                BoundedContextPersistence.Entry(stranger, 1, 0L, versionOf = canonical[2].contextEntryKey()),
            ),
        )
        assertNull(BoundedContextPersistence.encode(misnamed))
    }

    @Test
    fun `two shuffles of the whole playlist start differently and cover every row`() {
        val first = QueueWindow(554, shuffleSeed = 1L).startAt(10, shuffle = true)
        val second = QueueWindow(554, shuffleSeed = 2L).startAt(10, shuffle = true)
        // Only a 25-row window is ever materialised, but it is drawn from a
        // permutation of every canonical row.
        val firstOrder = first.snapshot().order
        assertEquals(554, firstOrder.size)
        assertEquals((0 until 554).toSet(), firstOrder.toSet())
        assertNotEquals((0 until 554).toList(), firstOrder)
        assertNotEquals(
            first.upcoming(25).map { it.canonicalIndex },
            second.upcoming(25).map { it.canonicalIndex },
        )
    }

    private fun mv(index: Int) = Song(
        videoId = "mv-$index",
        title = "Title $index",
        artist = "Artist",
        thumbnailUrl = "https://img/$index",
        durationText = "3:00",
    ).copy(
        queueTier = QueueTier.CONTEXT,
        setVideoId = "set-$index",
        isVideo = true,
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
    )

    private fun anchored(canonical: List<Song>, index: Int, id: String) =
        BoundedContextPersistence.Entry(canonical[index].copy(queueEntryId = id), index, 0L)

    private fun state(
        canonical: List<Song>,
        window: QueueWindowSnapshot,
        entries: List<BoundedContextPersistence.Entry>,
        currentIndex: Int,
    ) = BoundedContextPersistence.State(
        context = ContextSnapshot(
            contextToken = "context-token",
            sourceKey = "${source.type.name}:${source.id}",
            browseId = source.id,
            songs = canonical,
            version = 3L,
            continuationToken = null,
            continuationState = ContinuationState.EXHAUSTED,
        ),
        source = source,
        window = window,
        entries = entries,
        currentIndex = currentIndex,
        positionMs = 0L,
    )
}
