package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The youtube.com fan-playlist upload shape: `Artist - Title | Work OST -
 * Original Soundtrack`, credited to the uploader. A Joker OST playlist played
 * all 34 of its uploads as video versions because the pipe tail and the
 * artist-title head were read as one title — the audio switch asked for the
 * uploader's name, and every candidate came back `reason=title`.
 *
 * The work tail is packaging; the head follows the upload's own "Artist -
 * Title" convention; and when the title names no singer at all, the release
 * is found by asking for `<title> <work> soundtrack` rather than for the
 * uploader.
 */
class TrackMatcherOstUploadTitleTest {

    private fun upload(
        title: String,
        uploader: String = "OST Archive",
        duration: String? = "5:05",
    ) = Song(
        videoId = "upload",
        title = title,
        artist = uploader,
        thumbnailUrl = null,
        durationText = duration,
    )

    @Test
    fun `an artist - title head over a work tail splits singer and song`() {
        val raw =
            "Lawrence Welk - The Moon Is a Silver Dollar | Joker OST - Original Soundtrack"
        val parts = TrackMatcher.parseTitle(raw, "OST Archive")
        assertEquals("themoonisasilverdollar", parts.core)
        assertEquals("lawrence welk", parts.byline)
        assertEquals("joker", parts.work)

        val target = TrackMatcher.targetOf(upload(raw, duration = "3:05"))
        assertEquals("lawrence welk", target.artist)
        assertEquals(
            listOf("the moon is a silver dollar lawrence welk", "the moon is a silver dollar"),
            TrackMatcher.queries(target),
        )
    }

    @Test
    fun `a title with no singer asks for the work soundtrack instead of the uploader`() {
        val raw = "Following Sophie | Joker OST"
        val parts = TrackMatcher.parseTitle(raw, "OST Archive")
        assertEquals("followingsophie", parts.core)
        assertNull(parts.byline)
        assertEquals("joker", parts.work)

        val target = TrackMatcher.targetOf(upload(raw, duration = "2:41"))
        // No singer was extracted, so the row's credit is untouched; it is
        // only the query that stops carrying it.
        assertEquals("OST Archive", target.artist)
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            TrackMatcher.queries(target),
        )
    }

    @Test
    fun `the extracted byline is the artist a catalogue row must share`() {
        val target = TrackMatcher.targetOf(upload("Cream - White Room | Joker OST"))
        assertEquals("cream", target.artist)
        assertEquals(
            listOf("white room cream", "white room"),
            TrackMatcher.queries(target),
        )
        val release = song("catalogue", "White Room", "Cream", "5:03")
        assertEquals(release, TrackMatcher.bestSameArtist(listOf(release), target))
        assertNull(TrackMatcher.songVersionRejectReason(release, target))
        // The same-artist rule itself is unchanged: a catalogue row credited to
        // the uploader is still not the singer the title named.
        val uploaderRow = song("uploader", "White Room", "OST Archive", "5:03")
        assertNull(TrackMatcher.bestSameArtist(listOf(uploaderRow), target))
    }

    @Test
    fun `a two-artist byline asks for its first credit`() {
        val raw = "Hildur Guðnadóttir, Jóhann Jóhannsson - The Joker | Joker OST"
        val parts = TrackMatcher.parseTitle(raw, "OST Archive")
        assertEquals("thejoker", parts.core)
        assertEquals("hildur guðnadóttir, jóhann jóhannsson", parts.byline)
        assertEquals(
            listOf("the joker hildur guðnadóttir", "the joker"),
            TrackMatcher.queries(TrackMatcher.targetOf(upload(raw, duration = "2:12"))),
        )
    }

    @Test
    fun `a tail that is a take marker or packaging keeps the ordinary reading`() {
        // "Title - Live Version" is a take of the song, not an artist.
        val live = TrackMatcher.parseTitle("Song - Live Version | Joker OST", "OST Archive")
        assertEquals("song", live.core)
        assertEquals(setOf("live", "version"), live.versions)
        assertNull(live.byline)

        // A remaster note is packaging, and a digits-only tail is not a title.
        val remaster =
            TrackMatcher.parseTitle("Bohemian Rhapsody - Remastered 2011 | Joker OST", "OST Archive")
        assertEquals("bohemianrhapsody", remaster.core)
        assertNull(remaster.byline)

        // "Song - Original Soundtrack" has no work name to ask for.
        val plain = TrackMatcher.parseTitle("Song - Original Soundtrack", "OST Archive")
        assertEquals("song", plain.core)
        assertNull(plain.work)
    }

    @Test
    fun `an ost tail after a dash also names the work for the query`() {
        val raw = "Following Sophie - Joker OST"
        val parts = TrackMatcher.parseTitle(raw, "OST Archive")
        assertEquals("followingsophie", parts.core)
        assertNull(parts.byline)
        assertEquals("joker", parts.work)
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            TrackMatcher.queries(TrackMatcher.targetOf(upload(raw, duration = "2:41"))),
        )
    }

    @Test
    fun `official MV tails keep their parsing`() {
        assertEquals("youngblood", TrackMatcher.parseTitle("Youngblood (Official MV)").core)
        assertEquals("youngblood", TrackMatcher.parseTitle("Youngblood M/V").core)
        assertEquals("youngblood", TrackMatcher.parseTitle("Youngblood | Official Video").core)
        assertEquals(
            listOf("youngblood 5 seconds of summer", "youngblood"),
            TrackMatcher.queries(TrackMatcher.Target("Youngblood M/V", "5 Seconds of Summer")),
        )
        // A video tail carries no work name, so the credit is asked as before.
        assertEquals(
            listOf("song ost archive", "song"),
            TrackMatcher.queries(TrackMatcher.Target("Song | Official Video", "OST Archive")),
        )
    }

    @Test
    fun `a pipe tail without a work marker changes nothing`() {
        val parts = TrackMatcher.parseTitle("Artist - Title | HD", "Uploader")
        assertEquals("artist", parts.core)
        assertNull(parts.byline)
        assertNull(parts.work)
    }

    @Test
    fun `a credit that already holds the head stays the fuller spelling`() {
        // "Dae" is the head the upload printed, but the row's credit "Dae
        // Sung" already names that singer and is the better matcher input.
        val target = TrackMatcher.targetOf(upload("Dae - Song | Joker OST", uploader = "Dae Sung"))
        assertEquals("Dae Sung", target.artist)
        assertEquals(
            listOf("song dae sung", "song"),
            TrackMatcher.queries(target),
        )
    }

    @Test
    fun `a cover keeps its own credit and its refusal`() {
        // A cover row records the credit it was read with; the byline rule
        // never replaces it.
        val cover = upload("Cover Singer - Song | Joker OST").copy(
            originalTitle = "Cover Singer - Song | Joker OST",
            originalArtist = "Uploader",
        )
        assertEquals("Uploader", TrackMatcher.targetOf(cover).artist)

        // A cover candidate is still refused for an OST-tail target.
        val target = TrackMatcher.targetOf(upload("Cream - White Room | Joker OST"))
        val coverRow = song("cover", "White Room", "White Room Cover Band", "5:03")
        assertNull(TrackMatcher.bestSameArtist(listOf(coverRow), target))
        assertEquals("cover", TrackMatcher.songVersionRejectReason(coverRow, target))
    }

    @Test
    fun `a korean work tail splits a hangul byline and asks in korean`() {
        val raw = "성시경 - 우리의 밤 | 사랑의 이해 OST"
        val parts = TrackMatcher.parseTitle(raw, "OST Archive")
        assertEquals("우리의밤", parts.core)
        assertEquals("성시경", parts.byline)
        assertEquals("사랑의 이해", parts.work)
        assertEquals(
            listOf("우리의 밤 성시경", "우리의 밤"),
            TrackMatcher.queries(TrackMatcher.targetOf(upload(raw, duration = "4:03"))),
        )
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String?,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )
}
