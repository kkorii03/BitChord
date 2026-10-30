package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayableAlternateTest {

    private fun video(id: String, title: String = "Song", artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, isVideo = true)

    private fun song(id: String, title: String = "Song", artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `a late song version is used for an unplayable video`() {
        val failed = video("7QihqRWMrz8")
        val late = song("DUQUqfL_4sg")
        assertEquals(
            late,
            UnplayableAlternate.cachedSongVersion(
                row = failed,
                cached = late,
                downloaded = false,
                unplayable = false,
            ),
        )
    }

    @Test
    fun `video radio, covers and downloads keep their own upload`() {
        val late = song("DUQUqfL_4sg")
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = video("7QihqRWMrz8").copy(videoRadio = true),
                cached = late,
                downloaded = false,
                unplayable = false,
            ),
        )
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = video("7QihqRWMrz8", title = "Song (Cover)"),
                cached = late,
                downloaded = false,
                unplayable = false,
            ),
        )
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = video("7QihqRWMrz8"),
                cached = late,
                downloaded = true,
                unplayable = false,
            ),
        )
    }

    @Test
    fun `the failed id, another video or an already refused copy is not a rescue`() {
        val failed = video("7QihqRWMrz8")
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = failed,
                cached = song("7QihqRWMrz8"),
                downloaded = false,
                unplayable = false,
            ),
        )
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = failed,
                cached = video("DUQUqfL_4sg"),
                downloaded = false,
                unplayable = false,
            ),
        )
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = failed,
                cached = song("DUQUqfL_4sg"),
                downloaded = false,
                unplayable = true,
            ),
        )
    }

    @Test
    fun `only a video or an unknown-type row is eligible for a song version`() {
        val late = song("DUQUqfL_4sg")
        assertTrue(UnplayableAlternate.allowsSongVersion(video("7QihqRWMrz8"), downloaded = false))
        assertTrue(
            UnplayableAlternate.allowsSongVersion(
                video("7QihqRWMrz8").copy(isVideo = false, typeUnknown = true),
                downloaded = false,
            ),
        )
        assertNull(
            UnplayableAlternate.cachedSongVersion(
                row = song("7QihqRWMrz8"),
                cached = late,
                downloaded = false,
                unplayable = false,
            ),
        )
    }

    @Test
    fun `the replacement search never waits past its budget`() {
        assertEquals(2_500L, UnplayableAlternate.searchBudgetMs(elapsedMs = 0L, budgetMs = 2_500L))
        assertEquals(1_000L, UnplayableAlternate.searchBudgetMs(elapsedMs = 1_500L, budgetMs = 2_500L))
        assertEquals(0L, UnplayableAlternate.searchBudgetMs(elapsedMs = 2_500L, budgetMs = 2_500L))
        assertEquals(0L, UnplayableAlternate.searchBudgetMs(elapsedMs = 9_000L, budgetMs = 2_500L))
        assertEquals(0L, UnplayableAlternate.searchBudgetMs(elapsedMs = -10L, budgetMs = 0L))
    }
}
