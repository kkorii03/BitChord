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

    // ── A title filed under another script's name (11-05 '체포하겠어') ────────
    //
    // Deezer listed ILLIT's "체포하겠어" as "You're Under Arrest" — same artist,
    // 3:01 against 3:01 — and the exact-title gate dropped the FLAC before the
    // sound comparison was ever asked about it. The listener was told there was
    // no better copy.

    private val arrest = TrackMatcher.Target("체포하겠어", "ILLIT", durationSec = 181)

    @Test
    fun `a title in another script is title-unverified when artist and length agree`() {
        val english = candidate(artist = "ILLIT", title = "You're Under Arrest", duration = "3:01")
        assertTrue(TrackMatcher.isTitleUnverifiedOriginal(english, arrest))
        assertEquals(
            "title-unverified",
            TrackMatcher.rejectionReason(
                english,
                arrest,
                strictOriginal = true,
                strictLength = true,
                requireSharedArtist = true,
                driftSec = 2,
                allowArtistUnverified = true,
            ),
        )
    }

    @Test
    fun `without the upgrade flag an other-script title is still a plain title miss`() {
        val english = candidate(artist = "ILLIT", title = "You're Under Arrest", duration = "3:01")
        assertEquals(
            "title",
            TrackMatcher.rejectionReason(english, arrest, strictOriginal = true),
        )
        assertFalse(
            "only the sound comparison may admit this row",
            TrackMatcher.isOriginalRecording(english, arrest, maxDriftSec = 3),
        )
    }

    @Test
    fun `a different song from the same search is never title-unverified`() {
        // LastWave answered the same search with 'Takedown' by HUNTR/X at 3:02.
        // The title is in another script and the length agrees to the second,
        // so the artist is the only thing keeping it out — and it must.
        val takedown = candidate(
            artist = "HUNTR/X, EJAE, AUDREY NUNA, REI AMI, KPop Demon Hunters Cast",
            title = "Takedown",
            duration = "3:02",
        )
        assertFalse(TrackMatcher.isTitleUnverifiedOriginal(takedown, arrest))
        assertEquals(
            "title",
            TrackMatcher.rejectionReason(
                takedown,
                arrest,
                strictOriginal = true,
                requireSharedArtist = true,
                allowArtistUnverified = true,
            ),
        )
    }

    @Test
    fun `a same-script different title is never title-unverified`() {
        // Same act, same length, but the title is written in the same script
        // and says something else: that is a different song, not a translation.
        val other = candidate(artist = "ILLIT", title = "자유롭게", duration = "3:01")
        assertFalse(TrackMatcher.isTitleUnverifiedOriginal(other, arrest))
    }

    @Test
    fun `a kana title of the same length is not admitted as a localised title`() {
        // Hangul against kana is a release's Japanese version far more often
        // than a localised title, and the chroma comparison cannot tell two
        // vocal languages of one melody apart. Only Latin against a non-Latin
        // request is admitted.
        val japanese = candidate(artist = "ILLIT", title = "タイトル", duration = "3:01")
        assertFalse(TrackMatcher.isTitleUnverifiedOriginal(japanese, arrest))
    }

    @Test
    fun `a same-act other-script title of the same length is left to the sound comparison`() {
        // The script test can only prove the *request* is not written in the
        // row's script; it cannot tell "You're Under Arrest" from another Latin
        // title by the same act. Such a row is therefore offered to hq-verify
        // as well. This is the accepted cost of the grade: an extra download
        // for a wrong row, never a wrong recording playing — the comparison
        // still has to accept it.
        val sameLength = candidate(artist = "ILLIT", title = "Tick-Tack", duration = "3:01")
        assertTrue(TrackMatcher.isTitleUnverifiedOriginal(sameLength, arrest))
    }

    @Test
    fun `an other-script title outside the three-second window is not unverified`() {
        val long = candidate(artist = "ILLIT", title = "You're Under Arrest", duration = "3:12")
        assertFalse(TrackMatcher.isTitleUnverifiedOriginal(long, arrest))
    }
}
