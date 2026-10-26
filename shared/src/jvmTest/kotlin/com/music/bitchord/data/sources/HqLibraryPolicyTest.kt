package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HqLibraryPolicyTest {

    private fun entry(
        id: String,
        current: HqTier? = null,
        verified: HqTier? = HqTier.LOSSLESS_16,
        source: String? = "flac-server",
        track: String? = "track-$id",
        plays: Int = 0,
        liked: Boolean = false,
    ) = HqLibraryEntry(
        videoId = id,
        currentTier = current,
        verifiedTier = verified,
        sourceConfigId = source,
        trackId = track,
        plays = plays,
        liked = liked,
    )

    @Test
    fun `a stored hi-res tier outranks a FLAC badge that cannot state depth`() {
        val current = HqLibraryPolicy.currentTier(
            badgeTier = HqTier.LOSSLESS_16,
            storedHqTier = HqTier.HI_RES_24_88,
        )
        assertEquals(HqTier.HI_RES_24_88, current)
        val plan = HqLibraryPolicy.select(
            listOf(entry("song", current = current, verified = HqTier.HI_RES_24_88)),
            prefetchLimit = 500,
        )
        assertTrue(plan.replace.isEmpty())
    }

    @Test
    fun `a downloaded file is replaced only by a strictly higher verified rung`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("lower", current = HqTier.LOSSLESS_16, verified = HqTier.HIGH_LOSSY),
                entry("equal", current = HqTier.LOSSLESS_16, verified = HqTier.LOSSLESS_16),
                entry("higher", current = HqTier.LOSSLESS_16, verified = HqTier.HI_RES_24_44),
                entry("atmos", current = HqTier.DOLBY_ATMOS, verified = HqTier.HI_RES_24_88),
            ),
            prefetchLimit = 500,
        )
        assertEquals(listOf("higher"), plan.replace.map { it.videoId })
        assertEquals(HqTier.HI_RES_24_44, plan.replace.single().verifiedTier)
    }

    @Test
    fun `an already high-quality download is still replaced by a higher verified rung`() {
        val plan = HqLibraryPolicy.select(
            listOf(entry("song", current = HqTier.HI_RES_24_44, verified = HqTier.HI_RES_24_88)),
            prefetchLimit = 500,
        )
        assertEquals(listOf("song"), plan.replace.map { it.videoId })
    }

    @Test
    fun `only high-quality verified copies are prefetched, not normal ones`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("normal", verified = HqTier.NORMAL),
                entry("lossy", verified = HqTier.HIGH_LOSSY),
                entry("lossless", verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = 500,
        )
        assertEquals(listOf("lossy", "lossless").sorted(), plan.prefetch.map { it.videoId }.sorted())
        assertTrue(plan.replace.isEmpty())
    }

    @Test
    fun `favourites are ordered liked first then most played`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("rare", plays = 1),
                entry("often", plays = 40),
                entry("liked-once", plays = 0, liked = true),
                entry("liked-often", plays = 10, liked = true),
            ),
            prefetchLimit = 500,
        )
        assertEquals(
            listOf("liked-often", "liked-once", "often", "rare"),
            plan.prefetch.map { it.videoId },
        )
    }

    @Test
    fun `the prefetch cap applies after ordering and never limits replacement`() {
        val entries = (1..10).map { index ->
            entry("prefetch-$index", verified = HqTier.LOSSLESS_16, plays = index)
        } + (1..3).map { index ->
            entry("replace-$index", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16)
        }
        val plan = HqLibraryPolicy.select(entries, prefetchLimit = 4)
        assertEquals(4, plan.prefetch.size)
        assertEquals(
            listOf("prefetch-10", "prefetch-9", "prefetch-8", "prefetch-7"),
            plan.prefetch.map { it.videoId },
        )
        assertEquals(3, plan.replace.size)
    }

    @Test
    fun `a zero cap disables prefetch but keeps replacement`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("download", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
                entry("not-downloaded", verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = 0,
        )
        assertEquals(listOf("download"), plan.replace.map { it.videoId })
        assertTrue(plan.prefetch.isEmpty())
    }

    @Test
    fun `switches turn each half off independently`() {
        val entries = listOf(
            entry("download", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
            entry("not-downloaded", verified = HqTier.LOSSLESS_16),
        )
        val replaceOnly = HqLibraryPolicy.select(entries, 500, prefetchEnabled = false)
        assertEquals(listOf("download"), replaceOnly.replace.map { it.videoId })
        assertTrue(replaceOnly.prefetch.isEmpty())

        val prefetchOnly = HqLibraryPolicy.select(entries, 500, replaceEnabled = false)
        assertTrue(prefetchOnly.replace.isEmpty())
        assertEquals(listOf("not-downloaded"), prefetchOnly.prefetch.map { it.videoId })
    }

    @Test
    fun `an unverified or unaddressable pair is ignored`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("unverified", verified = null),
                entry("no-source", source = null),
                entry("no-track", track = ""),
                entry("blank-id", verified = HqTier.LOSSLESS_16).copy(videoId = " "),
            ),
            prefetchLimit = 500,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `duplicate ids are acted on once`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("song", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16, plays = 1),
                entry("song", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16, plays = 9),
            ),
            prefetchLimit = 500,
        )
        assertEquals(1, plan.replace.size)
    }

    @Test
    fun `an arrangement source is kept out of the library pass`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("song", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16)
                    .copy(
                        songTitle = "Song",
                        songArtist = "Artist",
                        sourceTitle = "Song (Piano Cover)",
                        sourceArtist = "Piano Dream",
                    ),
            ),
            prefetchLimit = 500,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `the best verified rung wins when one song has several proved rows`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("song", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16, source = "first"),
                entry("song", current = HqTier.NORMAL, verified = HqTier.HI_RES_24_88, source = "better"),
            ),
            prefetchLimit = 500,
        )
        assertEquals(HqTier.HI_RES_24_88, plan.replace.single().verifiedTier)
        assertEquals("better", plan.replace.single().sourceConfigId)
    }
}
