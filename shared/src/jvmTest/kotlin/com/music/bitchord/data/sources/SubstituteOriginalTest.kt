package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
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

    /**
     * The eight rows from the JioSaavn cover report, kept verbatim. The ninth
     * row in the test above is an original-artist instrumental rather than one
     * of the catalogue's arrangements, so it is checked separately.
     */
    @Test
    fun `the eight real cover cases from the report are refused`() {
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
        )
        assertEquals(8, covers.size)
        covers.forEach { candidate ->
            assertFalse(
                "must not substitute '${candidate.title}' by '${candidate.artist}' " +
                    "from '${candidate.albumName}'",
                TrackMatcher.isOriginalRecording(candidate, nextLevel),
            )
        }
    }

    /**
     * The report's allowed example: the same title, artist, release and runtime
     * is the record, and must not be refused along with the covers.
     */
    @Test
    fun `the watermelon sugar original from fine line is accepted`() {
        val target = TrackMatcher.Target(
            "Watermelon Sugar",
            "Harry Styles",
            durationSec = 173,
            album = "Fine Line",
        )
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song(
                    "Watermelon Sugar",
                    "Harry Styles",
                    duration = "2:53",
                    album = "Fine Line",
                ),
                target,
            ),
        )
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

    @Test
    fun `a substitute may drift by the five second gate but not past it`() {
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("Next Level", "aespa", duration = "3:46"),
                nextLevel,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("Next Level", "aespa", duration = "3:47"),
                nextLevel,
            ),
        )
    }

    @Test
    fun `a substitute without a runtime cannot pass a timed request`() {
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("Next Level", "aespa", duration = null),
                nextLevel,
            ),
        )
        // With no runtime on the request there is nothing to disagree with.
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("Next Level", "aespa", duration = null),
                nextLevel.copy(durationSec = null),
            ),
        )
    }

    @Test
    fun `a no-vocal album is refused even when the title is plain`() {
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song(
                    "Next Level",
                    "aespa",
                    duration = "3:41",
                    album = "Next Level (Instrumental Edition)",
                ),
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
