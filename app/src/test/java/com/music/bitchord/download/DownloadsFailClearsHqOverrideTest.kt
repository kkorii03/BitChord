package com.music.bitchord.download

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.ko.downloads.KoDownloadQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A failed replacement must not keep the pinned source/track pair.
 *
 * The next tap passes `hq = null`, so [Downloads.prepare] may only fall back to
 * an ordinary download when the stale override is gone. Otherwise a source or
 * track that has disappeared makes every retry fail the same way before it can
 * reach YouTube.
 */
class DownloadsFailClearsHqOverrideTest {

    private val failedId = "video-1"
    private val untouchedId = "video-2"

    private val hqField = KoDownloadQueue::class.java
        .getDeclaredField("hqOverrides")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val overrides = hqField.get(KoDownloadQueue) as MutableMap<String, HqDownloadSource>

    @Before
    fun setUp() {
        overrides.clear()
    }

    @After
    fun tearDown() {
        overrides.clear()
        DownloadSession.forget(failedId)
    }

    @Test
    fun `a failed download drops its hq override so the retry can fall back`() {
        overrides[failedId] = HqDownloadSource(
            sourceConfigId = "source",
            trackId = "track",
            tier = HqTier.LOSSLESS_16,
        )
        overrides[untouchedId] = HqDownloadSource(
            sourceConfigId = "other-source",
            trackId = "other-track",
            tier = HqTier.HI_RES_24_44,
        )

        fail(failedId, "source is gone")

        assertFalse(overrides.containsKey(failedId))
        assertTrue("another row's override is not this failure's business", overrides.containsKey(untouchedId))
        assertEquals(
            DownloadState.Failed("source is gone"),
            Downloads.active.value[failedId],
        )
    }

    @Test
    fun `a transient network failure keeps the verified pair for the retry`() {
        val pair = HqDownloadSource(
            sourceConfigId = "source",
            trackId = "track",
            tier = HqTier.LOSSLESS_16,
        )
        overrides[failedId] = pair

        fail(failedId, "Unable to resolve host \"example.com\"", keepVerifiedPair = true)

        assertEquals(
            "the pair is still the answer once the connection returns",
            pair,
            overrides[failedId],
        )
        assertEquals(
            DownloadState.Failed("Unable to resolve host \"example.com\""),
            Downloads.active.value[failedId],
        )
    }

    private fun fail(videoId: String, reason: String) {
        Downloads.fail(videoId, reason)
    }

    private fun fail(videoId: String, reason: String, keepVerifiedPair: Boolean) {
        Downloads.fail(videoId, reason, keepVerifiedPair)
    }
}
