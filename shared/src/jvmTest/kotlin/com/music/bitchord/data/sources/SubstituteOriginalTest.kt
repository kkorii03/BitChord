package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate a substitute source has to clear before it may play instead of
 * YouTube: the row has to be the original recording, not a cover catalogue's
 * arrangement filed under the same title.
 */
class SubstituteOriginalTest {

    private fun song(
        title: String,
        artist: String,
        duration: String? = "3:41",
        album: String? = null,
    ) = Song(
        videoId = "$title|$artist|$album",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        albumName = album,
    )

    private val nextLevel = TrackMatcher.Target("Next Level", "aespa", durationSec = 221)

    @Test
    fun `all nine jiosaavn next level covers are refused`() {
        val covers = listOf(
            song("Next Level", "The Cat and Owl", album = "Lullaby Versions of Aespa"),
            song("Next Level", "Vita", album = "Kpop Idol Guitar"),
            song(
                "Next Level (16-Bit Aespa Emulation)",
                "Arcade Player",
                album = "16-Bit Aespa Emulation",
            ),
            song("Next Level (8-Bit Aespa Emulation)", "8-Bit Arcade"),
            song(
                "Next Level (Originally Performed by Aespa)",
                "Backing Business",
                album = "Pristine Karaoke",
            ),
            song("Next Level (Karaoke Version)", "Karaoke Galaxy"),
            song("Next Level (Piano Version)", "Piano Dreamers"),
            song("Next Level (Tribute to Aespa)", "Tribute Band"),
            song("Next Level (Instrumental)", "aespa"),
        )
        covers.forEach { candidate ->
            assertFalse(
                "must not substitute '${candidate.title}' by '${candidate.artist}' " +
                    "from '${candidate.albumName}'",
                TrackMatcher.isOriginalRecording(candidate, nextLevel),
            )
        }
    }

    @Test
    fun `the original recording of the same length is accepted`() {
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("Next Level", "aespa", duration = "3:41", album = "Next Level"),
                nextLevel,
            ),
        )
    }

    @Test
    fun `an instrumental is refused even from the original artist`() {
        val ditto = TrackMatcher.Target("Ditto", "aespa", durationSec = 185)
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("Ditto (Instrumental)", "aespa", duration = "3:05"),
                ditto,
            ),
        )
    }

    @Test
    fun `the same artist twenty seconds away is a different recording`() {
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("Next Level", "aespa", duration = "4:01"),
                nextLevel,
            ),
        )
    }

    /**
     * The reported 'Watermelon Sugar' failure: the artist column is the
     * original singer, so a name-only check passes; the title and album say
     * 'Originally performed by', which is the cover catalogue's tell.
     */
    @Test
    fun `a same artist cover marker is refused`() {
        val target = TrackMatcher.Target(
            "Watermelon Sugar",
            "Harry Styles",
            durationSec = 173,
            album = "Fine Line",
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song(
                    "Watermelon sugar (Originally performed by Harry Styles)",
                    "Harry Styles",
                    duration = "2:55",
                    album = "Watermelon sugar (Originally performed by Harry Styles)",
                ),
                target,
            ),
        )
    }

    @Test
    fun `a cover marker in the album alone is refused`() {
        assertTrue(
            TrackMatcher.isCoverArrangement(
                song("Next Level", "Vita", album = "Kpop Idol Guitar"),
                song("Next Level", "aespa"),
            ),
        )
    }
}
