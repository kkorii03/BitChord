package com.music.bitchord.download

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.ko.downloads.KoDownloadPipeline
import com.music.bitchord.ko.downloads.KoDownloadQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * G9 (phone log 11-13): a downloaded file condemned as the wrong recording
 * could never be repaired when it came from a high-quality source. The repair
 * falls back to a YouTube-only fetch, and its floor was the WRONG file's own
 * rung (`minimumTier = currentTier`), so [KoDownloadPipeline.requireMinimumTier]
 * refused every YouTube route (F50yfwMmAAc 320 kbps, itBbxKe6Z50 FLAC).
 *
 * On the old code the floor of a condemned HIGH_LOSSY file was HIGH_LOSSY and
 * [a wrong recording's repair lands the YouTube song version] failed.
 */
class HqRepairFloorTest {

    private val id = "g9-repair"

    @After
    fun tearDown() {
        KoDownloadQueue.clear(id)
    }

    private fun route(tier: HqTier?) = Downloads.Route(
        extension = "opus",
        mimeType = "audio/ogg",
        describe = "160kbps audio/webm",
        tier = tier,
        recordingVerified = true,
        write = { _, _ -> },
    )

    @Test
    fun `the old floor refused the YouTube song version for a 320 kbps wrong file`() {
        // What the repair asked for before the fix: the wrong file's own rung.
        assertThrows(IllegalStateException::class.java) {
            KoDownloadPipeline.requireMinimumTier(route(HqTier.NORMAL), HqTier.HIGH_LOSSY)
        }
    }

    @Test
    fun `a wrong recording's repair lands the YouTube song version`() {
        for (wrongFileTier in HqTier.values()) {
            val floor = hqRepairFloor(fileIsWrongRecording = true, currentTier = wrongFileTier)
            assertEquals(HqTier.NORMAL, floor)
            // Must not throw: the right recording at 160 kbps beats a wrong one at any rung.
            KoDownloadPipeline.requireMinimumTier(route(HqTier.NORMAL), floor)
        }
    }

    @Test
    fun `a wrong recording's repair still refuses a route of unknown rung`() {
        val floor = hqRepairFloor(fileIsWrongRecording = true, currentTier = HqTier.LOSSLESS_16)
        assertThrows(IllegalStateException::class.java) {
            KoDownloadPipeline.requireMinimumTier(route(null), floor)
        }
    }

    @Test
    fun `a file that is not condemned keeps its own rung as the floor`() {
        val floor = hqRepairFloor(fileIsWrongRecording = false, currentTier = HqTier.HIGH_LOSSY)
        assertEquals(HqTier.HIGH_LOSSY, floor)
        assertThrows(IllegalStateException::class.java) {
            KoDownloadPipeline.requireMinimumTier(route(HqTier.NORMAL), floor)
        }
        KoDownloadPipeline.requireMinimumTier(route(HqTier.HIGH_LOSSY), floor)
        KoDownloadPipeline.requireMinimumTier(route(HqTier.LOSSLESS_16), floor)
    }

    @Test
    fun `the lowered floor reaches prepare and a manager retry`() {
        val floor = hqRepairFloor(fileIsWrongRecording = true, currentTier = HqTier.HIGH_LOSSY)
        // Downloads.enqueue -> remember -> prepare reads minimumTierFor.
        KoDownloadQueue.remember(
            id = id,
            quality = null,
            hq = null,
            avoidSourceConfigId = "wrong-source",
            forceReplace = true,
            youtubeOnly = true,
            minimumTier = floor,
            automatic = true,
            mvSoundRepair = false,
        )
        assertEquals(HqTier.NORMAL, KoDownloadQueue.minimumTierFor(id))
        // Downloads.retry repeats the plan built at enqueue time.
        val plan = KoDownloadQueue.retryPlanFor(
            allowMetered = false,
            quality = null,
            hq = null,
            avoidSourceConfigId = "wrong-source",
            forceReplace = true,
            youtubeOnly = true,
            minimumTier = floor,
            mvSoundRepair = false,
        )
        assertEquals(HqTier.NORMAL, plan.minimumTier)
    }
}
