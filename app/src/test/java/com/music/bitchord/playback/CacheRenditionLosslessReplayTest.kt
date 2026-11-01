package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which copy a restart reopens once the in-memory stream choice is gone.
 *
 * The reported failure: an hq-prefetched lossless rendition finished while the
 * app was still on the previous track, the process died before the low-quality
 * copy was dropped, and the next launch played Opus even though the lossless
 * bytes were still whole on disk. The cache selection must prefer the finished
 * lossless copy by what is on disk, without any process memory.
 */
class CacheRenditionLosslessReplayTest {

    private val videoId = "dQw4w9WgXcQ"

    private fun copy(
        key: String,
        complete: Boolean,
        lossless: Boolean = false,
        upgradeOrder: Int = 0,
    ) = CachedRendition(key, complete, lossless, upgradeOrder)

    @Test
    fun `a complete lossless sibling with no upgrade tag beats the complete opus copy`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                copy(videoId, complete = true),
                copy("$videoId#alt", complete = true, lossless = true),
            ),
        )

        assertEquals("$videoId#alt", chosen)
    }

    @Test
    fun `an incomplete lossless sibling leaves the complete opus copy alone`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                copy(videoId, complete = true),
                copy("$videoId#alt", complete = false, lossless = true),
            ),
        )

        assertEquals(videoId, chosen)
    }

    @Test
    fun `a complete lossy upgrade still beats the base copy`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                copy(videoId, complete = true),
                copy("$videoId#hifi", complete = true, upgradeOrder = 1),
            ),
        )

        assertEquals("$videoId#hifi", chosen)
    }
}
