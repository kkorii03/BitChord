package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayCoverFilterTest {

    private fun song(title: String, artist: String) =
        Song(videoId = title + artist, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `karaoke cover and tribute rows are dropped from a station`() {
        val seed = song("Wannabe", "Spice Girls")
        assertTrue(TrackMatcher.isCoverArrangement(song("Wannabe (Karaoke Version)", "Sing King"), seed))
        assertTrue(TrackMatcher.isCoverArrangement(song("Wannabe", "Tribute Band"), seed))
        assertTrue(TrackMatcher.isCoverArrangement(song("Wannabe - Cover", "Some Singer"), seed))
    }

    @Test
    fun `the original recording is kept`() {
        val seed = song("Wannabe", "Spice Girls")
        assertFalse(TrackMatcher.isCoverArrangement(song("Wannabe", "Spice Girls"), seed))
        assertFalse(TrackMatcher.isCoverArrangement(song("Spice Up Your Life", "Spice Girls"), seed))
    }

    @Test
    fun `a seed that itself carries the marker keeps it`() {
        assertFalse(
            TrackMatcher.isCoverArrangement(
                song("Cover Me", "Bruce Springsteen"),
                song("Cover Me", "Bruce Springsteen"),
            ),
        )
    }
}
