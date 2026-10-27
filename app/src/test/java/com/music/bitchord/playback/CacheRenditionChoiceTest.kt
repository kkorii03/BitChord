package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which rendition a plain play request opens when more than one copy is on disk.
 *
 * The bug this covers: a low-quality Opus entry from an earlier play is complete,
 * so the old key factory returned it without ever looking at the lossless copy
 * written later under `videoId#hifi`. It stayed that way forever.
 */
class CacheRenditionChoiceTest {

    private val videoId = "dQw4w9WgXcQ"

    private fun copy(
        key: String,
        complete: Boolean,
        lossless: Boolean = false,
        upgradeOrder: Int = 0,
    ) = CachedRendition(key, complete, lossless, upgradeOrder)

    @Test
    fun `low quality only stays on the low quality copy`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(copy(videoId, complete = true)),
        )

        assertEquals(videoId, chosen)
    }

    @Test
    fun `a complete lossless copy wins over a complete low quality one`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                copy(videoId, complete = true),
                copy("$videoId#hifi", complete = true, lossless = true, upgradeOrder = 1),
            ),
        )

        assertEquals("$videoId#hifi", chosen)
    }

    @Test
    fun `a partial lossless copy does not displace the complete low quality one`() {
        val chosen = CacheRenditionChoice.choose(
            preferred = videoId,
            candidates = listOf(
                copy(videoId, complete = true),
                copy("$videoId#hifi", complete = false, lossless = true, upgradeOrder = 1),
            ),
        )

        assertEquals(videoId, chosen)
    }
}
