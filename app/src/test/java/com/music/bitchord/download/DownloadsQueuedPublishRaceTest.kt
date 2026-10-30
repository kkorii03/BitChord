package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A refusal that wins the race with [Downloads.enqueue] must not be overwritten
 * by a later "Queued" publish.
 *
 * [Downloads.onForeground] can take a row back and mark it failed after the
 * enqueue inserted it into `pending` but before it published `Queued`. The
 * publish is allowed only while the id is still pending.
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

    @Before
    fun setUp() {
        pending.clear()
        active.value = emptyMap()
    }

    @After
    fun tearDown() {
        pending.clear()
        active.value = emptyMap()
    }

    @Test
    fun `a queued publish does not overwrite a refusal after the row was taken back`() {
        val videoId = "video-1"
        pending[videoId] = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )

        publishQueued(videoId)
        assertEquals(DownloadState.Queued, active.value[videoId])

        pending.remove(videoId)
        active.value = mapOf(videoId to DownloadState.Failed("refused"))

        publishQueued(videoId)
        assertEquals(DownloadState.Failed("refused"), active.value[videoId])
    }

    private fun publishQueued(videoId: String) {
        val method = Downloads::class.java.getDeclaredMethod(
            "publishQueuedIfPending",
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(Downloads, videoId)
    }
}
