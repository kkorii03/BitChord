package com.music.bitchord.ko

import com.music.bitchord.playback.CacheRenditionChoice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The restart case for a lossless copy that landed under `#alt`.
 *
 * The `bc_hq_lossless` marker is only written by the completed `#hifi` fetch,
 * so a FLAC/WAV that another path wrote under `#alt` has no marker at all. The
 * candidate must recognise it from the file's own container, and must still
 * refuse a foreign addon's copy the same way [AudioCache.completeSibling] does.
 */
class KoRenditionCandidateTest {

    private val videoId = "dQw4w9WgXcQ"

    private fun candidate(
        key: String,
        complete: Boolean = true,
        markedLossless: Boolean = false,
        container: String? = null,
        keptOrigin: Boolean = true,
        upgradeOrder: Int = 0,
    ) = losslessCacheCandidate(
        key = key,
        complete = complete,
        markedLossless = markedLossless,
        container = container,
        keptOrigin = keptOrigin,
        upgradeOrder = upgradeOrder,
    )

    @Test
    fun `a complete flac alt without the marker beats the complete opus copy`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                candidate(videoId),
                candidate("$videoId#alt", container = "flac"),
            ),
        )

        assertEquals("$videoId#alt", chosen)
    }

    @Test
    fun `a partial flac alt leaves the complete opus copy alone`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                candidate(videoId),
                candidate("$videoId#alt", complete = false, container = "flac"),
            ),
        )

        assertEquals(videoId, chosen)
    }

    @Test
    fun `a foreign addon flac alt does not override the configured copy`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                candidate(videoId),
                candidate("$videoId#alt", container = "flac", keptOrigin = false),
            ),
        )

        assertEquals(videoId, chosen)
    }

    @Test
    fun `a complete hifi fetch still wins without a container read`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                candidate(videoId),
                candidate("$videoId#hifi", markedLossless = true, upgradeOrder = 1),
            ),
        )

        assertEquals("$videoId#hifi", chosen)
    }
}
