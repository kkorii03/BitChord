package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A permanent failure must leave Retry able to search again.
 *
 * [Downloads.fail] dropped the in-memory pinned pair, but [Downloads.retry]
 * rebuilt the ask from the manager row's plan, which still carried that pair.
 * Every tap then re-pinned the same dead source and failed again before a fresh
 * search could run.
 */
class DownloadsPermanentFailureRetryPlanTest {

    private fun song(id: String = "video") =
        Song(videoId = id, title = "Title", artist = "Artist", thumbnailUrl = null)

    private val pair = HqDownloadSource(
        sourceConfigId = "dead-source",
        trackId = "dead-track",
        tier = HqTier.LOSSLESS_16,
    )

    @Before
    fun setUp() = DownloadSession.clear()

    @After
    fun tearDown() = DownloadSession.clear()

    @Test
    fun `a permanent failure drops the pinned pair but keeps what was asked for`() {
        DownloadSession.queued(
            song(),
            retry = DownloadRetryPlan(
                quality = DownloadQuality.HIGH,
                hq = pair,
                avoidSourceConfigId = "avoided-source",
                forceReplace = true,
            ),
        )

        fail("video", "source is gone")

        val plan = DownloadSession.state.value.items.single().retry
        assertNull("retry must be free to search again", plan?.hq)
        assertNull("the avoided source no longer belongs to this ask", plan?.avoidSourceConfigId)
        assertEquals(DownloadQuality.HIGH, plan?.quality)
        assertTrue(plan?.forceReplace == true)
    }

    @Test
    fun `a transient failure keeps the pinned pair for the retry`() {
        DownloadSession.queued(song(), retry = DownloadRetryPlan(hq = pair, forceReplace = true))

        fail("video", "Unable to resolve host \"example.com\"", keepVerifiedPair = true)

        val plan = DownloadSession.state.value.items.single().retry
        assertEquals(pair, plan?.hq)
        assertFalse(plan?.hq == null)
        assertTrue(plan?.forceReplace == true)
    }

    private fun fail(videoId: String, reason: String) {
        val method = Downloads::class.java.getDeclaredMethod(
            "fail",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(Downloads, videoId, reason)
    }

    private fun fail(videoId: String, reason: String, keepVerifiedPair: Boolean) {
        val method = Downloads::class.java.getDeclaredMethod(
            "fail",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        method.invoke(Downloads, videoId, reason, keepVerifiedPair)
    }
}
