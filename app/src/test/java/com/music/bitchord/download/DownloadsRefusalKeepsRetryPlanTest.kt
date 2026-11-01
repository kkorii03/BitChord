package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A refusal that never contacted the verified pair must leave the upgrade
 * intent on the row, or Retry becomes an ordinary download.
 *
 * [Downloads.fail] used to drop the pinned pair on every failure, including the
 * Wi-Fi-only and service-start refusals. The source and track were never tried,
 * so on an allowed connection Retry should resume the upgrade instead of
 * adopting the old lower-rung file and reporting Done.
 */
class DownloadsRefusalKeepsRetryPlanTest {

    private fun song(id: String = "video") =
        Song(videoId = id, title = "Title", artist = "Artist", thumbnailUrl = null)

    private val pair = HqDownloadSource(
        sourceConfigId = "verified-source",
        trackId = "verified-track",
        tier = HqTier.LOSSLESS_16,
    )

    private val hqField = Downloads::class.java
        .getDeclaredField("hqOverrides")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val overrides = hqField.get(Downloads) as MutableMap<String, HqDownloadSource>

    @Before
    fun setUp() {
        overrides.clear()
        DownloadSession.clear()
    }

    @After
    fun tearDown() {
        overrides.clear()
        DownloadSession.clear()
    }

    @Test
    fun `a Wi-Fi-only refusal keeps the pinned pair for the retry`() {
        queueReplacementAndOverride()

        fail("video", Downloads.WIFI_ONLY_REFUSAL)

        val plan = DownloadSession.state.value.items.single().retry
        assertEquals(pair, plan?.hq)
        assertEquals(pair, overrides["video"])
        assertTrue(plan?.forceReplace == true)
    }

    @Test
    fun `a service-start refusal keeps the pinned pair for the retry`() {
        queueReplacementAndOverride()

        fail("video", "지금은 다운로드를 시작할 수 없습니다")

        val plan = DownloadSession.state.value.items.single().retry
        assertEquals(pair, plan?.hq)
        assertEquals(pair, overrides["video"])
        assertTrue(plan?.forceReplace == true)
    }

    @Test
    fun `a permanent source failure still frees the next tap to search again`() {
        queueReplacementAndOverride()

        fail("video", "source is gone")

        val plan = DownloadSession.state.value.items.single().retry
        assertNull(plan?.hq)
        assertNull(overrides["video"])
        assertEquals(DownloadQuality.HIGH, plan?.quality)
        assertTrue(plan?.forceReplace == true)
    }

    private fun queueReplacementAndOverride() {
        overrides["video"] = pair
        DownloadSession.queued(
            song(),
            retry = DownloadRetryPlan(
                quality = DownloadQuality.HIGH,
                hq = pair,
                forceReplace = true,
            ),
        )
    }

    private fun fail(videoId: String, reason: String) {
        val method = Downloads::class.java.getDeclaredMethod(
            "fail",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(Downloads, videoId, reason)
    }
}
