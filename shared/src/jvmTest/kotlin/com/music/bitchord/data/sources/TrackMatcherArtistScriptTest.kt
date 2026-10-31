package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A catalogue may localise an artist's name into another script (Qobuz returns
 * "Flowers/マイリー・サイラス" for Miley Cyrus). That row is not a different
 * artist, but the credit cannot be compared either. It may survive as
 * `artist-unverified` only when title, take and length all agree, and it still
 * needs the sound comparison before it can replace anything.
 */
class TrackMatcherArtistScriptTest {

    private fun candidate(
        artist: String,
        duration: String = "3:20",
        title: String = "Flowers",
    ) = Song(
        videoId = "$title|$artist|$duration",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private val target = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    @Test
    fun `a localised artist is artist-unverified when title and length agree`() {
        val localised = candidate("マイリー・サイラス")
        assertTrue(TrackMatcher.isArtistUnverifiedOriginal(localised, target))
        assertEquals(
            "artist-unverified",
            TrackMatcher.rejectionReason(
                localised,
                target,
                strictOriginal = true,
                strictLength = true,
                driftSec = TrackMatcher.ARTIST_UNVERIFIED_DRIFT_SEC,
                allowArtistUnverified = true,
            ),
        )
    }

    @Test
    fun `a different artist in the same script is still rejected`() {
        val other = candidate("Bruno Mars")
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(other, target))
        assertEquals(
            "artist",
            TrackMatcher.rejectionReason(
                other,
                target,
                strictOriginal = true,
                allowArtistUnverified = true,
            ),
        )
    }

    @Test
    fun `a tribute row is a version rejection even across scripts`() {
        val tribute = candidate(
            artist = "マイリー・サイラス",
            title = "Flowers - Tribute to Miley Cyrus",
        )
        assertEquals(
            "version",
            TrackMatcher.rejectionReason(
                tribute,
                target,
                strictOriginal = true,
                allowArtistUnverified = true,
            ),
        )
    }

    @Test
    fun `a featuring bracket on the requested title is ignored`() {
        val withFeaturing = TrackMatcher.Target(
            "Flowers (feat. Someone)",
            "Miley Cyrus",
            durationSec = 200,
        )
        assertTrue(
            TrackMatcher.isArtistUnverifiedOriginal(
                candidate("マイリー・サイラス"),
                withFeaturing,
            ),
        )
    }

    @Test
    fun `a runtime more than three seconds away is not artist-unverified`() {
        val long = candidate("マイリー・サイラス", duration = "3:24")
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(long, target))
        assertEquals(
            "duration",
            TrackMatcher.rejectionReason(
                long,
                target,
                strictOriginal = true,
                strictLength = true,
                driftSec = 2,
                allowArtistUnverified = true,
            ),
        )
    }

    @Test
    fun `the default replacement gate still refuses an unverified artist`() {
        val localised = candidate("マイリー・サイラス")
        assertFalse(
            "only the sound comparison may admit this row",
            TrackMatcher.isOriginalRecording(localised, target),
        )
        assertTrue(
            TrackMatcher.isOriginalRecording(
                localised,
                target,
                allowArtistUnverified = true,
            ),
        )
    }
}
