package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The judgements [TrackMatcher] makes beyond the substitute-source gate:
 * identity, runtime windows, catalogue collisions and the best-of choice.
 */
class TrackMatcherJudgementTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = null,
        album: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        albumName = album,
    )

    private val target = TrackMatcher.Target("Next Level", "aespa", durationSec = 221)

    @Test
    fun `sameRecording accepts the record and refuses a cover arrangement`() {
        assertTrue(
            TrackMatcher.sameRecording(
                song("original", "Next Level", "aespa", "3:41"),
                target,
            ),
        )
        assertFalse(
            TrackMatcher.sameRecording(
                song("cover", "Next Level (Karaoke Version)", "Karaoke Galaxy", "3:41"),
                target,
            ),
        )
        assertFalse(
            TrackMatcher.sameRecording(
                song("other", "Next Level", "Someone Else", "3:41"),
                target,
            ),
        )
    }

    @Test
    fun `withinSeconds needs both runtimes and counts the boundary`() {
        assertTrue(TrackMatcher.withinSeconds(song("a", "Next Level", "aespa", "3:46"), target, 5))
        assertFalse(TrackMatcher.withinSeconds(song("b", "Next Level", "aespa", "3:47"), target, 5))
        assertFalse(TrackMatcher.withinSeconds(song("c", "Next Level", "aespa", null), target, 5))
        assertFalse(
            TrackMatcher.withinSeconds(
                song("d", "Next Level", "aespa", "3:41"),
                target.copy(durationSec = null),
                5,
            ),
        )
    }

    @Test
    fun `a severe mismatch starts past thirty seconds`() {
        assertFalse(TrackMatcher.isSevereMismatch(221, 251))
        assertTrue(TrackMatcher.isSevereMismatch(221, 252))
        assertTrue(TrackMatcher.isSevereMismatch(221, 190))
        assertFalse(TrackMatcher.isSevereMismatch(null, 252))
        assertFalse(TrackMatcher.isSevereMismatch(221, null))
    }

    @Test
    fun `secondsOf reads minutes and hours but rejects nonsense`() {
        assertEquals(221, TrackMatcher.secondsOf("3:41"))
        assertEquals(3_723, TrackMatcher.secondsOf("1:02:03"))
        assertEquals(221, TrackMatcher.secondsOf(" 3:41 "))
        assertNull(TrackMatcher.secondsOf(null))
        assertNull(TrackMatcher.secondsOf("3"))
        assertNull(TrackMatcher.secondsOf("0:00"))
        assertNull(TrackMatcher.secondsOf("3:xx"))
    }

    @Test
    fun `best prefers the closer runtime among same-title rows`() {
        val close = song("close", "Next Level", "aespa", "3:42")
        val far = song("far", "Next Level", "aespa", "4:01")
        val cover = song("cover", "Next Level", "The Cat and Owl", "3:41", "Lullaby Versions of Aespa")

        assertEquals(close, TrackMatcher.best(listOf(far, cover, close), target))
    }

    @Test
    fun `conflicting releases are only a problem when the request names none`() {
        val one = song("one", "Next Level", "aespa", "3:41", "Album One")
        val two = song("two", "Next Level", "aespa", "3:41", "Album Two")
        val same = song("same", "Next Level", "aespa", "3:41", "Album One")

        assertTrue(TrackMatcher.hasConflictingAlbums(listOf(one, two), target))
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(one, same), target))
        assertFalse(
            TrackMatcher.hasConflictingAlbums(
                listOf(one, two),
                target.copy(album = "Album One"),
            ),
        )
    }

    @Test
    fun `the fuller credit resolves a close collision only when it is unique`() {
        val oneCredit = song("one", "Next Level", "aespa", "3:41", "Album One")
        val twoCredits = song("two", "Next Level", "aespa, Someone Else", "3:42", "Album Two")
        val alsoTwo = song("three", "Next Level", "aespa, Another", "3:42", "Album Three")

        assertEquals(
            twoCredits,
            TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(oneCredit, twoCredits), target),
        )
        assertNull(
            TrackMatcher.uniquelyMostCreditedCloseMatch(
                listOf(oneCredit, twoCredits, alsoTwo),
                target,
            ),
        )
    }

    @Test
    fun `sameTitle keeps the take marker apart`() {
        val plain = song("a", "Next Level", "aespa", "3:41")
        val live = song("b", "Next Level (Live)", "aespa", "3:41")

        assertTrue(TrackMatcher.sameTitle(plain, song("c", "Next Level (Official Video)", "aespa")))
        assertFalse(TrackMatcher.sameTitle(plain, live))
    }
}
