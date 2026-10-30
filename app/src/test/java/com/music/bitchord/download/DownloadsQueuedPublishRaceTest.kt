package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A refusal that wins the race with [Downloads.enqueue] must not be overwritten
 * by a later "Queued" publish.
 *
 * [Downloads.onForeground] can take a row back and mark it failed after the
 * enqueue inserted it into `pending` but before it published `Queued`. The
 * publish is allowed only while the id is still pending, and the pending check,
 * the `active` row and the download manager's row must all be written under
 * the same lock so neither can be left showing Queued with nothing draining it.
 */
class DownloadsQueuedPublishRaceTest {

    private val pendingField = Downloads::class.java
        .getDeclaredField("pending")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val pending = pendingField.get(Downloads) as MutableMap<String, Song>

    private val activeField = Downloads::class.java
        .getDeclaredField("_active")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val active = activeField.get(Downloads) as MutableStateFlow<Map<String, DownloadState>>

    private val enqueueGenerationsField = Downloads::class.java
        .getDeclaredField("enqueueGenerations")
        .apply { isAccessible = true }

    private val claimedGenerationsField = Downloads::class.java
        .getDeclaredField("claimedGenerations")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val enqueueGenerations =
        enqueueGenerationsField.get(Downloads) as MutableMap<String, Long>

    @Suppress("UNCHECKED_CAST")
    private val claimedGenerations =
        claimedGenerationsField.get(Downloads) as MutableMap<String, Long>

    @Before
    fun setUp() {
        pending.clear()
        active.value = emptyMap()
        enqueueGenerations.clear()
        claimedGenerations.clear()
        DownloadSession.clear()
    }

    @After
    fun tearDown() {
        Downloads.onStopped()
        pending.clear()
        active.value = emptyMap()
        enqueueGenerations.clear()
        claimedGenerations.clear()
        DownloadSession.clear()
    }

    @Test
    fun `a queued publish does not overwrite a refusal after the row was taken back`() {
        val videoId = "video-1"
        val song = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )
        pending[videoId] = song

        publishQueued(song)
        assertEquals(DownloadState.Queued, active.value[videoId])
        assertEquals(DownloadProgress.Queued, progressOf(videoId))

        pending.remove(videoId)
        active.value = mapOf(videoId to DownloadState.Failed("refused"))
        DownloadSession.failed(videoId, "refused")

        publishQueued(song)
        assertEquals(DownloadState.Failed("refused"), active.value[videoId])
        assertTrue(progressOf(videoId) is DownloadProgress.Failed)
    }

    @Test
    fun `a queued publish still reaches the manager after the service claimed the row`() {
        val videoId = "video-2"
        val song = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )
        pending[videoId] = song

        // The service can wake between enqueue inserting the row and the
        // Queued publish. It moves the id from pending to running, so a
        // pending-only guard would silently skip the manager row and every
        // later running/done update would find nothing to update.
        assertEquals(song, Downloads.takeNext())

        publishQueued(song)
        assertEquals(DownloadProgress.Queued, progressOf(videoId))
    }

    @Test
    fun `a claimed row advanced past queued is not published back to queued`() {
        val videoId = "video-3"
        val song = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )
        pending[videoId] = song

        // The Queue publish happens under the same lock as the insert, so a
        // claimed row always has a manager row to advance. A cache-backed
        // download can then finish before any late publish; writing Queued now
        // would move it backwards with nothing left to advance it again.
        DownloadSession.queued(song)
        assertEquals(song, Downloads.takeNext())
        DownloadSession.running(videoId, 0f)
        active.value = emptyMap()
        DownloadSession.done(videoId)

        publishQueued(song)
        assertEquals(null, active.value[videoId])
        assertEquals(DownloadProgress.Done, progressOf(videoId))
    }

    @Test
    fun `a claimed row that already failed is not republished as queued`() {
        val videoId = "video-4"
        val song = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )
        pending[videoId] = song

        DownloadSession.queued(song)
        assertEquals(song, Downloads.takeNext())
        active.value = mapOf(videoId to DownloadState.Failed("no such track"))
        DownloadSession.failed(videoId, "no such track")

        publishQueued(song)
        assertEquals(DownloadState.Failed("no such track"), active.value[videoId])
        assertTrue(progressOf(videoId) is DownloadProgress.Failed)
    }

    @Test
    fun `retrying a failed row publishes queued for the new enqueue`() {
        val videoId = "video-5"
        val song = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )
        // A finished attempt from before this enqueue. The new enqueue owns a
        // fresh generation, so the old Failed row must not make the manager
        // keep showing the failure until the worker happens to start.
        pending[videoId] = song
        enqueueGenerations[videoId] = 7L
        active.value = mapOf(videoId to DownloadState.Failed("old failure"))
        DownloadSession.failed(videoId, "old failure")

        publishQueued(song)

        assertEquals(DownloadState.Queued, active.value[videoId])
        assertEquals(DownloadProgress.Queued, progressOf(videoId))
    }

    private fun progressOf(videoId: String): DownloadProgress? =
        DownloadSession.state.value.items.firstOrNull { it.videoId == videoId }?.progress

    private fun publishQueued(song: Song) {
        val method = Downloads::class.java.getDeclaredMethod(
            "publishQueuedIfPending",
            Song::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        method.invoke(Downloads, song, null, true)
    }
}
