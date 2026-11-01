package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Retry in the download manager must repeat the errand that failed.
 *
 * The row used to keep only the song and release name, so retrying a verified
 * high-quality replacement went through an ordinary download — which can adopt
 * the old lower-rung file and report Done without the upgrade. The plan now
 * stays on the row until a new ask replaces it.
 */
class DownloadRetryPlanTest {

    private fun song(id: String = "video") =
        Song(videoId = id, title = "Title", artist = "Artist", thumbnailUrl = null)

    @Before
    fun setUp() = DownloadSession.clear()

    @After
    fun tearDown() = DownloadSession.clear()

    @Test
    fun `a failed replacement keeps the terms its retry must repeat`() {
        val plan = DownloadRetryPlan(
            quality = null,
            hq = HqDownloadSource(
                sourceConfigId = "source",
                trackId = "track",
                tier = HqTier.HI_RES_24_88,
            ),
            forceReplace = true,
        )

        DownloadSession.queued(song(), from = "Album", retry = plan)
        DownloadSession.failed("video", "network")

        val item = DownloadSession.state.value.items.single()
        assertEquals(plan, item.retry)
        assertEquals("Album", item.from)
        assertTrue(item.retry?.forceReplace == true)
    }

    @Test
    fun `an ordinary row has no replacement terms and stays ordinary`() {
        DownloadSession.queued(song())

        val item = DownloadSession.state.value.items.single()
        assertNull(item.retry)
        assertFalse(item.retry?.forceReplace == true)
    }

    @Test
    fun `a new ask replaces the old retry plan instead of inheriting it`() {
        DownloadSession.queued(
            song(),
            retry = DownloadRetryPlan(forceReplace = true),
        )
        DownloadSession.failed("video", "network")

        DownloadSession.queued(song(), retry = DownloadRetryPlan())

        val item = DownloadSession.state.value.items.single()
        assertFalse(item.retry?.forceReplace == true)
    }
}
