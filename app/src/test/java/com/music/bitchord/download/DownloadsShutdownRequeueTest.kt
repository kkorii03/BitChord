package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Android 15 ends a `dataSync` foreground service on its own schedule. The
 * workers must be cancelled, but the rows they had claimed must go back to the
 * queue with their request intact — otherwise up to four downloads vanish and
 * their manager rows stay "Running" forever.
 */
class DownloadsShutdownRequeueTest {

    private val videoId = "video-1"
    private val song = Song(
        videoId = videoId,
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
    )

    private val pendingField = field("pending")
    private val automaticField = field("automaticIds")
    private val mvRepairField = field("mvSoundRepairIds")
    private val requeuedField = field("requeuedForShutdown")

    @Suppress("UNCHECKED_CAST")
    private val pending = pendingField.get(Downloads) as MutableMap<String, Song>

    @Suppress("UNCHECKED_CAST")
    private val active = field("_active").get(Downloads) as MutableStateFlow<Map<String, DownloadState>>

    private val claimIdsField = field("claimIds")

    @Before
    fun setUp() {
        Downloads.onStopped()
        pending.clear()
        clearSet(automaticField)
        clearSet(mvRepairField)
        clearSet(requeuedField)
        clearMap(claimIdsField)
        active.value = emptyMap()
        DownloadSession.clear()
    }

    @After
    fun tearDown() {
        Downloads.onStopped()
        pending.clear()
        clearSet(automaticField)
        clearSet(mvRepairField)
        clearSet(requeuedField)
        clearMap(claimIdsField)
        active.value = emptyMap()
        DownloadSession.clear()
    }

    @Test
    fun `an in-flight automatic replacement is queued again with its request`() {
        add(automaticField)
        add(mvRepairField)
        pending[videoId] = song
        assertEquals(song, take().song)
        // [Downloads.run] removes these two when the transfer starts; a requeue
        // has to put them back or the retry is no longer a pinned replacement.
        remove(automaticField)
        remove(mvRepairField)

        active.value = mapOf(videoId to DownloadState.Running(0.25f))
        DownloadSession.queued(song)
        DownloadSession.running(videoId, 0.25f)

        Downloads.requeueInFlightForShutdown()

        assertEquals(song, pending[videoId])
        assertEquals(DownloadState.Queued, active.value[videoId])
        assertEquals(
            DownloadProgress.Queued,
            DownloadSession.state.value.items.single { it.videoId == videoId }.progress,
        )
        assertTrue(contains(automaticField))
        assertTrue(contains(mvRepairField))
    }

    @Test
    fun `a transfer that already finished is not put back in the queue`() {
        add(automaticField)
        pending[videoId] = song
        assertEquals(song, take().song)
        remove(automaticField)
        // A completed transfer clears its active row; the job may still be
        // between that clear and onIdle when the shutdown arrives.
        active.value = emptyMap()

        Downloads.requeueInFlightForShutdown()

        assertFalse(pending.containsKey(videoId))
        assertTrue(DownloadSession.state.value.items.isEmpty())
    }

    @Test
    fun `an automatic transfer without a manager row does not gain one`() {
        add(automaticField)
        pending[videoId] = song
        assertEquals(song, take().song)
        remove(automaticField)
        active.value = mapOf(videoId to DownloadState.Running(0.25f))

        Downloads.requeueInFlightForShutdown()

        assertEquals(song, pending[videoId])
        assertTrue(DownloadSession.state.value.items.isEmpty())
    }

    @Test
    fun `a requeued row is not claimed again while its old worker still owns it`() {
        pending[videoId] = song
        val first = take()
        active.value = mapOf(videoId to DownloadState.Running(0.25f))

        Downloads.requeueInFlightForShutdown()
        assertNull(Downloads.takeNext())

        Downloads.onStopped()
        val second = take()
        assertEquals(song, second.song)
        assertTrue(second.claimId != first.claimId)
    }

    @Test
    fun `a requeued transfer keeps its request when its old worker starts late`() {
        add(automaticField)
        add(mvRepairField)
        pending[videoId] = song
        val claim = take()
        // [run] removes the flags after [takeNext]; the shutdown's requeue puts
        // them back, but the old worker can still start and take them away
        // again before it sees the cancellation.
        remove(automaticField)
        remove(mvRepairField)
        active.value = mapOf(videoId to DownloadState.Running(0.25f))

        Downloads.requeueInFlightForShutdown()

        assertNull(Downloads.beginRun(videoId, claim.claimId))
        assertTrue(contains(automaticField))
        assertTrue(contains(mvRepairField))

        Downloads.onStopped()
        val retry = take()
        val flags = requireNotNull(Downloads.beginRun(videoId, retry.claimId))
        assertTrue(flags.automatic)
        assertTrue(flags.mvRepair)
    }

    @Test
    fun `a stale worker leaves the claim that replaced it alone`() {
        add(automaticField)
        pending[videoId] = song
        val old = take()
        active.value = mapOf(videoId to DownloadState.Running(0.25f))

        Downloads.requeueInFlightForShutdown()
        Downloads.onStopped()
        val replacement = take()

        // The old job can only find out it was cancelled after the new service
        // has already claimed the row. Its cleanup must be a no-op, and the
        // replacement's claim must stay intact until its own worker finishes.
        assertNull(Downloads.beginRun(videoId, old.claimId))
        Downloads.onIdle(videoId, old.claimId)
        assertNull(Downloads.takeNext())
        assertTrue(contains(automaticField))
        assertNotNull(Downloads.beginRun(videoId, replacement.claimId))
    }

    private fun take(): Downloads.Claim = requireNotNull(Downloads.takeNext())

    private fun field(name: String) = Downloads::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }

    private fun clearSet(field: java.lang.reflect.Field) {
        @Suppress("UNCHECKED_CAST")
        (field.get(Downloads) as MutableSet<String>).clear()
    }

    private fun clearMap(field: java.lang.reflect.Field) {
        @Suppress("UNCHECKED_CAST")
        (field.get(Downloads) as MutableMap<String, *>).clear()
    }

    private fun add(field: java.lang.reflect.Field) {
        @Suppress("UNCHECKED_CAST")
        (field.get(Downloads) as MutableSet<String>).add(videoId)
    }

    private fun remove(field: java.lang.reflect.Field) {
        @Suppress("UNCHECKED_CAST")
        (field.get(Downloads) as MutableSet<String>).remove(videoId)
    }

    private fun contains(field: java.lang.reflect.Field): Boolean {
        @Suppress("UNCHECKED_CAST")
        return (field.get(Downloads) as Set<String>).contains(videoId)
    }
}
