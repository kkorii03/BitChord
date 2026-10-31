package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.9 Direct Source / quality-upgrade path asks a source for a track and
 * decides with [TrackMatcher.ranked] (and therefore [TrackMatcher.score])
 * whether an answer is the same recording. That path relies on the
 * CREDITS_DISAGREE / CREDIT_OVERRIDE_SEC override when a catalogue writes an
 * artist in another script, so a cover with the same title and runtime must
 * not be able to use the same override.
 */
class TrackMatcherTrapCasesTest {

    private fun song(
        title: String,
        artist: String,
        duration: String,
    ) = Song(
        videoId = "$title|$artist|$duration",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private fun accepted(target: TrackMatcher.Target, candidate: Song): Boolean =
        TrackMatcher.ranked(listOf(candidate), target).isNotEmpty()

    private val cruelSummer = TrackMatcher.Target("Cruel Summer", "Taylor Swift", durationSec = 178)
    private val uptownFunk = TrackMatcher.Target("Uptown Funk", "Mark Ronson, Bruno Mars", durationSec = 270)
    private val flowers = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)
    private val dynamite = TrackMatcher.Target("Dynamite", "BTS", durationSec = 199)

    @Test
    fun `cruel summer by a string quartet is rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer", "Midnite String Quartet", "2:58"),
            ),
        )
    }

    @Test
    fun `cruel summer instrumental version is rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song(
                    "Cruel Summer [Originally Performed by Taylor Swift] (Instrumental Version)",
                    "Maxi Leave",
                    "2:58",
                ),
            ),
        )
    }

    @Test
    fun `cruel summer LP Giobbi remix is rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer (LP Giobbi Remix)", "Taylor Swift, LP Giobbi", "2:58"),
            ),
        )
    }

    @Test
    fun `cruel summer live from the eras tour is rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer (Live from TS | The Eras Tour)", "Taylor Swift", "2:58"),
            ),
        )
    }

    @Test
    fun `uptown funk tribute is rejected`() {
        assertFalse(
            accepted(
                uptownFunk,
                song(
                    "Uptown Funk - Tribute to Mark Ronson and Bruno Mars",
                    "Billboard Shakers",
                    "4:30",
                ),
            ),
        )
    }

    @Test
    fun `localised artist with a cover title is still rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer (Cover)", "テイラー・スウィフト", "2:58"),
            ),
        )
    }

    @Test
    fun `localised artist with a remix title is still rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer (Remix)", "テイラー・スウィフト", "2:58"),
            ),
        )
    }

    @Test
    fun `localised artist with a live title is still rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer (Live)", "テイラー・スウィフト", "2:58"),
            ),
        )
    }

    @Test
    fun `localised artist with an instrumental title is still rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song("Cruel Summer (Instrumental)", "テイラー・スウィフト", "2:58"),
            ),
        )
    }

    @Test
    fun `localised artist with a tribute title is still rejected`() {
        assertFalse(
            accepted(
                cruelSummer,
                song(
                    "Cruel Summer - Tribute to Taylor Swift",
                    "テイラー・スウィフト",
                    "2:58",
                ),
            ),
        )
    }

    @Test
    fun `flowers by a localised artist is accepted`() {
        assertTrue(
            accepted(
                flowers,
                song("Flowers", "マイリー・サイラス", "3:20"),
            ),
        )
    }

    @Test
    fun `dynamite by a localised artist is accepted`() {
        assertTrue(
            accepted(
                dynamite,
                song("Dynamite", "BTS (防弾少年団)", "3:19"),
            ),
        )
    }
}
