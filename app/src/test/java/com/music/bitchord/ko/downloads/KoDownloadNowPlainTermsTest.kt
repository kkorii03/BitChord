package com.music.bitchord.ko.downloads

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Playlist sync (`Downloads.downloadNow`) claims songs without going through
 * `enqueue`, so it never stated its terms: a song whose listener-started
 * replacement failed (failures keep forceReplace/youtubeOnly/minimumTier/quality
 * for the failure row) was synced YouTube-only with a minimum tier, or forced
 * over an existing file. The claim now states plain terms.
 */
class KoDownloadNowPlainTermsTest {

    private val id = "song-sync"

    @After
    fun tearDown() {
        KoDownloadQueue.clear(id)
    }

    @Test
    fun `a synced song does not inherit a failed replacement's terms`() {
        KoDownloadQueue.remember(
            id = id,
            quality = DownloadQuality.HIGH,
            hq = HqDownloadSource(sourceConfigId = "source", trackId = "track", tier = HqTier.LOSSLESS_16),
            avoidSourceConfigId = "wrong-source",
            forceReplace = true,
            youtubeOnly = true,
            minimumTier = HqTier.LOSSLESS_16,
            automatic = true,
            mvSoundRepair = true,
        )
        // The replacement failed; Downloads.fail leaves the terms. Sync claims it:
        KoDownloadQueue.rememberPlain(id)

        assertFalse("forceReplace leaked into playlist sync", KoDownloadQueue.forceReplaceFor(id))
        assertFalse("youtubeOnly leaked", KoDownloadQueue.youtubeOnlyFor(id))
        assertNull("minimumTier leaked", KoDownloadQueue.minimumTierFor(id))
        assertNull("quality leaked", KoDownloadQueue.qualityFor(id))
        assertNull("hq pair leaked", KoDownloadQueue.hqFor(id))
        assertNull("avoided source leaked", KoDownloadQueue.avoidSourceFor(id))
        assertFalse("automatic leaked", KoDownloadQueue.takeAutomatic(id))
        assertFalse("mv repair leaked", KoDownloadQueue.takeMvRepair(id))
    }

    @Test
    fun `a synced row's retry repeats an ordinary download`() {
        assertEquals(
            KoDownloadQueue.retryPlanFor(
                allowMetered = false,
                quality = null,
                hq = null,
                avoidSourceConfigId = null,
                forceReplace = false,
                youtubeOnly = false,
                minimumTier = null,
                mvSoundRepair = false,
            ),
            KoDownloadQueue.plainRetryPlan(),
        )
    }
}
