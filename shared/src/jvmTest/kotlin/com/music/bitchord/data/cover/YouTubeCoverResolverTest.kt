package com.music.bitchord.data.cover

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether an iTunes/Deezer answer is the same recording, with no network
 * involved. The cases are the ones the resolver must survive: packaging in a
 * title, a different credited artist, and a different take.
 */
class YouTubeCoverResolverTest {

    @BeforeTest
    fun setUp() {
        YouTubeCoverResolver.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        YouTubeCoverResolver.resetForTest()
    }

    @Test
    fun `a clean square match is accepted`() {
        assertTrue(YouTubeCoverResolver.accept("Shape of You", "Ed Sheeran", "Shape of You", "Ed Sheeran"))
        assertTrue(
            YouTubeCoverResolver.accept(
                "Shape of You (Official Video)",
                "Ed Sheeran",
                "Shape of You",
                "Ed Sheeran",
            ),
        )
        assertTrue(YouTubeCoverResolver.accept("밤편지", "아이유", "밤편지", "아이유"))
    }

    @Test
    fun `feat credits and topic suffixes do not prevent a match`() {
        assertTrue(
            YouTubeCoverResolver.accept(
                "Dynamite",
                "BTS - Topic",
                "Dynamite (feat. Guest)",
                "BTS",
            ),
        )
    }

    @Test
    fun `a different artist is rejected`() {
        assertFalse(
            YouTubeCoverResolver.accept(
                "Shape of You",
                "Ed Sheeran",
                "Shape of You",
                "Cover Singer",
            ),
        )
    }

    @Test
    fun `a different take is rejected`() {
        assertFalse(YouTubeCoverResolver.accept("Song", "Artist", "Song (Live)", "Artist"))
        assertFalse(YouTubeCoverResolver.accept("Song", "Artist", "Song (Remix)", "Artist"))
        assertFalse(YouTubeCoverResolver.accept("Song", "Artist", "A Different Song", "Artist"))
    }

    @Test
    fun `artist suffixes and artwork sizes are cleaned`() {
        assertEquals("Ed Sheeran", YouTubeCoverResolver.cleanArtist("Ed Sheeran - Topic"))
        assertEquals("Ed Sheeran", YouTubeCoverResolver.cleanArtist("Ed SheeranVEVO"))
        assertEquals("Ed Sheeran", YouTubeCoverResolver.cleanArtist("Ed Sheeran · Shape of You"))
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/600x600bb.jpg",
            YouTubeCoverResolver.squareArtwork(
                "https://is1-ssl.mzstatic.com/image/thumb/100x100bb.jpg",
            ),
        )
    }

    @Test
    fun `a settled miss is cached as none`() {
        val key = YouTubeCoverResolver.cacheKeyForTest("Shape of You", "Ed Sheeran")
        YouTubeCoverResolver.cachePutForTest(key, "none")
        assertEquals("none", YouTubeCoverResolver.cacheValueForTest(key))
    }

    @Test
    fun `the cover cache drops the oldest entry at its cap`() {
        repeat(YouTubeCoverResolver.MAX_ENTRIES + 1) { index ->
            YouTubeCoverResolver.cachePutForTest("k$index", "https://art/$index.jpg", index.toLong())
        }

        assertEquals(YouTubeCoverResolver.MAX_ENTRIES, YouTubeCoverResolver.cacheSizeForTest())
        assertNull(YouTubeCoverResolver.cacheValueForTest("k0"))
        assertEquals("https://art/1.jpg", YouTubeCoverResolver.cacheValueForTest("k1"))
    }
}
