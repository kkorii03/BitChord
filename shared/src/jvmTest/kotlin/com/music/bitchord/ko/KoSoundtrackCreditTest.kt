package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * 11-19 (Hi-Res addon session): a catalogue row credited only to a label —
 * "Original Soundtrack", "Various Artists", "드라마 OST" — never met the
 * performer's request (or the other way round, when YouTube Music printed the
 * label), so these songs never got a lossless copy. A label names nobody: it
 * is now an unknown credit, offered to the sound check as `artist-unverified`
 * only on an exact title, the same take and a runtime within two seconds. A
 * real but different artist, covers, other takes and other lengths stay
 * refused, and the label row never becomes a normal (unverified-free) match.
 */
class KoSoundtrackCreditTest {

    @get:Rule
    val koTimeout: Timeout = Timeout.seconds(60)

    private fun row(title: String, artist: String, sec: Int?, album: String? = null) = Song(
        videoId = "row-${(title + artist + sec).hashCode()}",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = album,
        durationText = sec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
    )

    private fun target(title: String, artist: String, sec: Int?) =
        TrackMatcher.Target(title, artist, durationSec = sec)

    /** The upgrade walk's own call: strict, shared artist, strict length, sound check allowed. */
    private fun verdict(candidate: Song, target: TrackMatcher.Target) = TrackMatcher.rejectionReason(
        candidate,
        target,
        strictOriginal = true,
        requireSharedArtist = true,
        strictLength = true,
        driftSec = 3,
        allowArtistUnverified = true,
    )

    private val performer = target("Bathroom Dance", "Hildur Guðnadóttir", 175)

    @Test
    fun `a label credit is offered to the sound check against the performer`() {
        val label = row("Bathroom Dance", "Original Soundtrack", 176)
        assertTrue(TrackMatcher.isArtistUnverifiedOriginal(label, performer))
        assertEquals("artist-unverified", verdict(label, performer))
    }

    @Test
    fun `a performer row is offered to the sound check against a label request`() {
        val request = target("Bathroom Dance", "Original Soundtrack", 175)
        val sung = row("Bathroom Dance", "Hildur Guðnadóttir", 175)
        assertTrue(TrackMatcher.isArtistUnverifiedOriginal(sung, request))
        assertEquals("artist-unverified", verdict(sung, request))
    }

    @Test
    fun `Various Artists and a Korean drama label are unknown credits too`() {
        val smile = target("Smile", "Jimmy Durante", 190)
        assertTrue(TrackMatcher.isArtistUnverifiedOriginal(row("Smile", "Various Artists", 191), smile))
        val night = target("Through the Night", "아이유", 255)
        assertTrue(TrackMatcher.isArtistUnverifiedOriginal(row("Through the Night", "드라마 OST", 256), night))
        assertEquals("artist-unverified", verdict(row("Through the Night", "드라마 OST", 256), night))
    }

    @Test
    fun `a label credit gets the tight two-second window, not three`() {
        val off3 = row("Bathroom Dance", "Original Soundtrack", 178)
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(off3, performer))
        // The log names the runtime, not the credit, as what refused it.
        assertEquals("duration", verdict(off3, performer))
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(row("Bathroom Dance", "Original Soundtrack", 190), performer))
        // A Hangul label against a Latin name used to pass at three seconds as a localised credit.
        val koreanLabel = target("Bathroom Dance", "오리지널 사운드트랙", 175)
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(row("Bathroom Dance", "Hildur Guðnadóttir", 178), koreanLabel))
        assertTrue(TrackMatcher.isArtistUnverifiedOriginal(row("Bathroom Dance", "Hildur Guðnadóttir", 177), koreanLabel))
    }

    @Test
    fun `an unknown runtime on either side admits nothing`() {
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(row("Bathroom Dance", "Original Soundtrack", null), performer))
        assertFalse(
            TrackMatcher.isArtistUnverifiedOriginal(
                row("Bathroom Dance", "Original Soundtrack", 175),
                target("Bathroom Dance", "Hildur Guðnadóttir", null),
            ),
        )
    }

    @Test
    fun `a real but different artist is still refused`() {
        val other = row("Bathroom Dance", "Joaquin Phoenix", 175)
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(other, performer))
        assertEquals("artist", verdict(other, performer))
        // A label beside a real name, or a named work, is not a bare label.
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(row("Bathroom Dance", "Joaquin Phoenix & Original Soundtrack", 175), performer))
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(row("Bathroom Dance", "Joker OST", 175), performer))
    }

    @Test
    fun `covers, karaoke, instrumental, piano, MR and other takes stay refused under a label`() {
        val refused = listOf(
            row("Bathroom Dance (Karaoke Version)", "Various Artists", 175),
            row("Bathroom Dance (Piano Cover)", "Original Soundtrack", 175),
            row("Bathroom Dance (Instrumental)", "Original Soundtrack", 175),
            row("Bathroom Dance (Piano)", "OST", 175),
            row("Bathroom Dance (MR)", "OST", 175),
            row("Bathroom Dance (Live)", "Original Soundtrack", 175),
            row("Bathroom Dance (English Version)", "Original Soundtrack", 175),
            row("Bathroom Dance", "Various Artists", 175, album = "Movie Themes (Karaoke Edition)"),
            row("Bathroom Dance", "Original Soundtrack", 175, album = "Joker (Instrumental Edition)"),
            row("Bathroom Dances", "Original Soundtrack", 175),
        )
        val admitted = refused.filter { TrackMatcher.isArtistUnverifiedOriginal(it, performer) }
        assertTrue("admitted: ${admitted.map { it.title }}", admitted.isEmpty())
    }

    @Test
    fun `two label credits at different lengths are refused`() {
        val request = target("Main Title", "Original Soundtrack", 120)
        val same = row("Main Title", "Original Soundtrack", 131)
        assertFalse(TrackMatcher.isOriginalRecording(same, request, allowArtistUnverified = true))
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(same, request))
        val other = row("Main Title", "Various Artists", 131)
        assertFalse(TrackMatcher.isOriginalRecording(other, request, allowArtistUnverified = true))
        assertFalse(TrackMatcher.isArtistUnverifiedOriginal(other, request))
    }

    @Test
    fun `a label row never outranks or stands in for the credited row`() {
        val label = row("Bathroom Dance", "Original Soundtrack", 175)
        val credited = row("Bathroom Dance", "Hildur Guðnadóttir", 177)
        assertNull(TrackMatcher.score(label, performer))
        assertEquals(listOf(credited), TrackMatcher.ranked(listOf(label, credited), performer))
        // Only the sound-check grade admits it; the plain replacement gate does not.
        assertFalse(TrackMatcher.isOriginalRecording(label, performer))
        assertTrue(TrackMatcher.isOriginalRecording(label, performer, allowArtistUnverified = true))
        assertTrue(TrackMatcher.isOriginalRecording(credited, performer))
    }

    @Test
    fun `what counts as a bare label`() {
        listOf(
            "Original Soundtrack", "OST", "Soundtrack", "Various Artists",
            "Original Motion Picture Soundtrack", "오리지널 사운드트랙", "드라마 OST", "여러 아티스트",
        ).forEach { assertTrue(it, KoTrackMatcher.isGenericCredit(it)) }
        listOf(
            "Joker OST", "Hildur Guðnadóttir", "The Weeknd", "Original", "Artists", "",
        ).forEach { assertFalse(it, KoTrackMatcher.isGenericCredit(it)) }
    }
}
