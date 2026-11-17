package com.music.bitchord.ko.downloads

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review D6: a listener-started replacement that failed permanently keeps its
 * forceReplace / youtubeOnly / minimumTier / quality terms (a failure does not
 * clear them). The next plain enqueue of the same song must state its own
 * terms, not inherit those. Old `remember` only added, so these failed.
 */
class KoDownloadQueueRememberResetsTermsTest {

    private val id = "song-d6"

    @After
    fun tearDown() {
        KoDownloadQueue.clear(id)
    }

    private fun rememberUpgrade() {
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
    }

    private fun rememberPlain() {
        KoDownloadQueue.remember(
            id = id,
            quality = null,
            hq = null,
            avoidSourceConfigId = null,
            forceReplace = false,
            youtubeOnly = false,
            minimumTier = null,
            automatic = false,
            mvSoundRepair = false,
        )
    }

    @Test
    fun `a plain enqueue after a failed replacement does not inherit its terms`() {
        rememberUpgrade()
        // The replacement failed: Downloads.fail does not call clear().
        rememberPlain()

        assertFalse("forceReplace leaked into an ordinary download", KoDownloadQueue.forceReplaceFor(id))
        assertFalse("youtubeOnly leaked", KoDownloadQueue.youtubeOnlyFor(id))
        assertNull("minimumTier leaked", KoDownloadQueue.minimumTierFor(id))
        assertNull("quality leaked", KoDownloadQueue.qualityFor(id))
        assertNull("hq pair leaked", KoDownloadQueue.hqFor(id))
        assertNull("avoided source leaked", KoDownloadQueue.avoidSourceFor(id))
        assertFalse("automatic leaked", KoDownloadQueue.takeAutomatic(id))
        assertFalse("mv repair leaked", KoDownloadQueue.takeMvRepair(id))
    }

    @Test
    fun `the enqueue's own terms are still recorded`() {
        rememberPlain()
        rememberUpgrade()

        assertTrue(KoDownloadQueue.forceReplaceFor(id))
        assertTrue(KoDownloadQueue.youtubeOnlyFor(id))
        assertEquals(HqTier.LOSSLESS_16, KoDownloadQueue.minimumTierFor(id))
        assertEquals(DownloadQuality.HIGH, KoDownloadQueue.qualityFor(id))
        assertEquals("track", KoDownloadQueue.hqFor(id)?.trackId)
        assertEquals("wrong-source", KoDownloadQueue.avoidSourceFor(id))
        assertTrue(KoDownloadQueue.takeAutomatic(id))
        assertTrue(KoDownloadQueue.takeMvRepair(id))
    }
}
