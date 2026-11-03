package com.music.bitchord.data.innertube

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * YouTube began answering HTTP 400 FAILED_PRECONDITION for the legacy visionOS
 * 0.1 profile in September 2026. It must not stay in the automatic catalog: the
 * S24 logs show it as the unavailable client right before "no playable
 * clients", once per extraction.
 */
class PlaybackClientCatalogVisionOsTest {

    @Test
    fun `legacy visionOS 0_1 is not asked automatically`() {
        val ids = PlaybackClientCatalog.automaticManifests.map { it.id }
        assertTrue("VISIONOS_0_1" !in ids, "automatic catalog still asks rejected VISIONOS_0_1: $ids")
    }
}
