package com.music.bitchord.data.innertube

import com.metrolist.innertubex.extraction.strategy.ClientSelectionMode
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The legacy visionOS 0.1 client answers every player request with a
 * playability failure, so the automatic catalog must not lead with it: doing
 * so spends one dead request per track and leaves the watch-page fallbacks to
 * retry behind it. Regression guard for the pinned InnerTubeX version.
 */
class InnerTubeXCatalogRegressionTest {

    @Test
    fun `the rejected legacy visionOS client is not in the automatic catalog`() {
        val manifest = assertNotNull(PlaybackClientCatalog.findManifest("VISIONOS_0_1"))
        assertFalse(manifest.selectionMode == ClientSelectionMode.AUTOMATIC)
        assertTrue(
            PlaybackClientCatalog.automaticManifests.none { it.id == "VISIONOS_0_1" },
            "VISIONOS_0_1 is deprecated and must only be reached by an explicit probe",
        )
    }
}
