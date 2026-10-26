package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection boundaries for the automatic high-quality pass: the rung that
 * counts as prefetchable, the cap, duplicate proofs, and ordering ties.
 */
class HqLibraryPolicyBoundaryTest {

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
    fun `better means a strictly lower rank`() {
        assertTrue(HqLibraryPolicy.isBetter(HqTier.NORMAL, HqTier.HIGH_LOSSY))
        assertTrue(HqLibraryPolicy.isBetter(HqTier.HIGH_LOSSY, HqTier.LOSSLESS_16))
        assertTrue(HqLibraryPolicy.isBetter(HqTier.HI_RES_24_44, HqTier.HI_RES_24_88))
        assertTrue(HqLibraryPolicy.isBetter(HqTier.HI_RES_24_88, HqTier.DOLBY_ATMOS))
        assertFalse(HqLibraryPolicy.isBetter(HqTier.LOSSLESS_16, HqTier.LOSSLESS_16))
        assertFalse(HqLibraryPolicy.isBetter(HqTier.HI_RES_24_88, HqTier.HI_RES_24_44))
        assertFalse(HqLibraryPolicy.isBetter(HqTier.DOLBY_ATMOS, HqTier.HI_RES_24_88))
    }

    @Test
    fun `the prefetch floor is high lossy and includes everything above it`() {
        assertFalse(HqLibraryPolicy.isWorthPrefetching(HqTier.NORMAL))
        assertTrue(HqLibraryPolicy.isWorthPrefetching(HqTier.HIGH_LOSSY))
        assertTrue(HqLibraryPolicy.isWorthPrefetching(HqTier.LOSSLESS_16))
        assertTrue(HqLibraryPolicy.isWorthPrefetching(HqTier.HI_RES_24_44))
        assertTrue(HqLibraryPolicy.isWorthPrefetching(HqTier.HI_RES_24_88))
        assertTrue(HqLibraryPolicy.isWorthPrefetching(HqTier.DOLBY_ATMOS))
    }

    @Test
    fun `an empty library is an empty plan`() {
        val plan = HqLibraryPolicy.select(emptyList(), prefetchLimit = 10)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.total)
        assertTrue(plan.replace.isEmpty())
        assertTrue(plan.prefetch.isEmpty())
    }

    @Test
    fun `a negative cap disables prefetch without touching replacement`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("download", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
                entry("not-downloaded", verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = -5,
        )
        assertEquals(listOf("download"), plan.replace.map { it.videoId })
        assertTrue(plan.prefetch.isEmpty())
    }

    @Test
    fun `both switches off always yields an empty plan`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("download", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
                entry("not-downloaded", verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = 100,
            replaceEnabled = false,
            prefetchEnabled = false,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `every unactionable shape is ignored`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("no-verified", verified = null),
                entry("blank-id", verified = HqTier.HI_RES_24_88).copy(videoId = ""),
                entry("spaced-id", verified = HqTier.HI_RES_24_88).copy(videoId = "  "),
                entry("no-source", source = null),
                entry("blank-source", source = ""),
                entry("spaced-source", source = "  "),
                entry("no-track", track = null),
                entry("blank-track", track = ""),
                entry("spaced-track", track = "  "),
            ),
            prefetchLimit = 100,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `replacement ignores an equal or lower verified rung`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("equal", current = HqTier.LOSSLESS_16, verified = HqTier.LOSSLESS_16),
                entry("lower", current = HqTier.LOSSLESS_16, verified = HqTier.HIGH_LOSSY),
                entry("normal-lower", current = HqTier.HIGH_LOSSY, verified = HqTier.NORMAL),
            ),
            prefetchLimit = 100,
        )
        assertTrue(plan.replace.isEmpty())
    }

    @Test
    fun `replacement accepts the top of the ladder and an unrated file`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("normal", current = HqTier.NORMAL, verified = HqTier.HIGH_LOSSY),
                entry("atmos", current = HqTier.HI_RES_24_88, verified = HqTier.DOLBY_ATMOS),
                entry("lossless", current = HqTier.HIGH_LOSSY, verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = 100,
        )
        assertEquals(listOf("normal", "atmos", "lossless").sorted(), plan.replace.map { it.videoId }.sorted())
    }

    @Test
    fun `replacement is ordered liked first then plays then id`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("z-liked", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16, liked = true),
                entry("a-liked", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16, liked = true),
                entry("played", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16, plays = 99),
                entry("tie-b", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
                entry("tie-a", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = 100,
        )
        assertEquals(
            listOf("a-liked", "z-liked", "played", "tie-a", "tie-b"),
            plan.replace.map { it.videoId },
        )
    }

    @Test
    fun `prefetch is ordered liked first then plays then id`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("z-liked", liked = true),
                entry("a-liked", liked = true),
                entry("played", plays = 99),
                entry("tie-b"),
                entry("tie-a"),
            ),
            prefetchLimit = 100,
        )
        assertEquals(
            listOf("a-liked", "z-liked", "played", "tie-a", "tie-b"),
            plan.prefetch.map { it.videoId },
        )
    }

    @Test
    fun `the cap is inclusive and applies after ordering`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("a", plays = 1),
                entry("b", plays = 2),
                entry("c", plays = 3),
            ),
            prefetchLimit = 2,
        )
        assertEquals(listOf("c", "b"), plan.prefetch.map { it.videoId })
    }

    @Test
    fun `a downloaded song is never prefetched and a missing one is never replaced`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("downloaded", current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
                entry("missing", current = null, verified = HqTier.LOSSLESS_16),
            ),
            prefetchLimit = 100,
        )
        assertEquals(listOf("downloaded"), plan.replace.map { it.videoId })
        assertEquals(listOf("missing"), plan.prefetch.map { it.videoId })
    }

    @Test
    fun `a missing song with a normal verified rung is not prefetched`() {
        val plan = HqLibraryPolicy.select(
            listOf(entry("normal", current = null, verified = HqTier.NORMAL)),
            prefetchLimit = 100,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `duplicate proofs use the best rung before any preference`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry(
                    "song",
                    current = HqTier.NORMAL,
                    verified = HqTier.HIGH_LOSSY,
                    source = "first",
                    liked = true,
                    plays = 100,
                ),
                entry(
                    "song",
                    current = HqTier.NORMAL,
                    verified = HqTier.HI_RES_24_88,
                    source = "better",
                    liked = false,
                    plays = 0,
                ),
            ),
            prefetchLimit = 100,
        )
        assertEquals(1, plan.replace.size)
        assertEquals(HqTier.HI_RES_24_88, plan.replace.single().verifiedTier)
        assertEquals("better", plan.replace.single().sourceConfigId)
        assertEquals("track-song", plan.replace.single().trackId)
    }

    @Test
    fun `duplicate proofs on equal rungs fall back to preference then ids`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry("song", verified = HqTier.LOSSLESS_16, source = "z", track = "z"),
                entry("song", verified = HqTier.LOSSLESS_16, source = "a", track = "b"),
                entry("song", verified = HqTier.LOSSLESS_16, source = "a", track = "a"),
            ),
            prefetchLimit = 100,
        )
        assertEquals(1, plan.prefetch.size)
        assertEquals("a", plan.prefetch.single().sourceConfigId)
        assertEquals("a", plan.prefetch.single().trackId)
    }

    @Test
    fun `the plan carries the proof into the target`() {
        val plan = HqLibraryPolicy.select(
            listOf(
                entry(
                    "song",
                    current = HqTier.NORMAL,
                    verified = HqTier.HI_RES_24_44,
                    source = "source-1",
                    track = "track-9",
                ),
            ),
            prefetchLimit = 100,
        )
        val target = plan.replace.single()
        assertEquals("song", target.videoId)
        assertEquals(HqLibraryAction.REPLACE, target.action)
        assertEquals(HqTier.HI_RES_24_44, target.verifiedTier)
        assertEquals("source-1", target.sourceConfigId)
        assertEquals("track-9", target.trackId)
        assertEquals(1, plan.total)
        assertFalse(plan.isEmpty)
    }
}
