package com.music.bitchord.ko.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review D3: a deliberate replacement written back over the same path keeps the
 * old uri. The old rule (`previousUris.any { it != newUri } || suspects`) said
 * "not replaced" for it, so the old recording's AI timing, heard words,
 * automatic recheck sheet and "already checked" mark stayed on the new bytes.
 */
class KoDownloadPipelineInPlaceReplaceTest {

    private val uri = "file:///data/user/0/com.music.bitchord/files/Music/a.flac"

    @Test
    fun `same uri rewritten by a replacement drops the old timing`() {
        // Old code: previousUris={uri}, newUri=uri, no suspects -> false.
        assertTrue(
            KoDownloadPipeline.dropsOldTiming(
                previousUris = setOf(uri),
                newUri = uri,
                replacing = true,
                suspects = emptyList(),
            ),
        )
    }

    @Test
    fun `same uri without a replacement keeps the timing`() {
        assertFalse(
            "an adopt rewrote nothing",
            KoDownloadPipeline.dropsOldTiming(
                previousUris = setOf(uri),
                newUri = uri,
                replacing = false,
                suspects = emptyList(),
            ),
        )
    }

    @Test
    fun `a new path or a length suspect still drops it as before`() {
        assertTrue(
            KoDownloadPipeline.dropsOldTiming(setOf("file:///old.m4a"), uri, replacing = false, suspects = emptyList()),
        )
        assertTrue(
            KoDownloadPipeline.dropsOldTiming(setOf(uri), uri, replacing = false, suspects = listOf("mv-id")),
        )
        assertFalse(
            "a first download has nothing to drop",
            KoDownloadPipeline.dropsOldTiming(emptySet(), uri, replacing = false, suspects = emptyList()),
        )
    }
}
