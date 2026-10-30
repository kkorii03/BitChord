package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before
    fun setUp() {
        Downloads.onStopped()
        pending.clear()
        clearSet(automaticField)
        clearSet(mvRepairField)
        clearSet(requeuedField)
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
        active.value = emptyMap()
        DownloadSession.clear()
    }

    @Test
    fun `an in-flight automatic replacement is queued again with its request`() {
        add(automaticField)
        add(mvRepairField)
        pending[videoId] = song
        assertEquals(song, Downloads.takeNext())
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
        assertEquals(song, Downloads.takeNext())
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
        assertEquals(song, Downloads.takeNext())
        remove(automaticField)
        active.value = mapOf(videoId to DownloadState.Running(0.25f))

        Downloads.requeueInFlightForShutdown()

        assertEquals(song, pending[videoId])
        assertTrue(DownloadSession.state.value.items.isEmpty())
    }

    @Test
    fun `a requeued row is not claimed again while its old worker still owns it`() {
        pending[videoId] = song
        assertEquals(song, Downloads.takeNext())
        active.value = mapOf(videoId to DownloadState.Running(0.25f))

        Downloads.requeueInFlightForShutdown()
        assertNull(Downloads.takeNext())

        Downloads.onStopped()
        assertEquals(song, Downloads.takeNext())
    }

    private fun field(name: String) = Downloads::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }

    private fun clearSet(field: java.lang.reflect.Field) {
        @Suppress("UNCHECKED_CAST")
        (field.get(Downloads) as MutableSet<String>).clear()
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
