package com.music.bitchord.data.sources

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The video-switch matcher: official videos beat fancams, stages and covers. */
class VideoSwitchMatcherTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = null,
        artistId: String? = null,
        artists: List<ArtistRef> = emptyList(),
        albumId: String? = null,
        album: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        artistId = artistId,
        artists = artists,
        albumId = albumId,
        albumName = album,
    )

    private val target = TrackMatcher.Target(
        title = "After LIKE",
        artist = "IVE",
        durationSec = 192,
        artistId = "UC_IVE",
    )

    @Test
    fun `a fancam cannot win over the official video`() {
        val fancam = song(
            "FANCAM00001",
            "[K-Fancam] \uC544\uC774\uBE0C \uC548\uC720\uC9C4 \uC9C1\uCEA0 After LIKE " +
                "(IVE YUJIN Fancam) | @MusicBank",
            "IVE",
            "3:12",
            "UC_IVE",
        )
        val official = song("OFFICIAL001", "After LIKE", "IVE", "3:12", "UC_IVE")

        assertEquals(
            official,
            TrackMatcher.bestForVideoSwitch(listOf(fancam, official), target),
        )
    }

    @Test
    fun `an official title wins between two otherwise valid rows`() {
        val plain = song("PLAIN000001", "After LIKE", "IVE", "3:12")
        val official = song("OFFICIAL002", "After LIKE (Official Video)", "IVE", "3:12", "UC_IVE")

        assertEquals(
            official,
            TrackMatcher.bestForVideoSwitch(listOf(plain, official), target),
        )
    }

    @Test
    fun `a stage or cover is skipped when the original title does not name it`() {
        val stage = song("STAGE000001", "After LIKE stage", "IVE", "3:12", "UC_IVE")
        val cover = song("COVER000001", "After LIKE cover", "Someone", "3:12")

        assertNull(TrackMatcher.bestForVideoSwitch(listOf(stage, cover), target))
    }

    @Test
    fun `a marker is allowed when the requested title names it`() {
        val live = TrackMatcher.Target(title = "After LIKE (Live)", artist = "IVE")
        val candidate = song("LIVE0000001", "After LIKE (Live)", "IVE", "3:12", "UC_IVE")

        assertNotNull(TrackMatcher.bestForVideoSwitch(listOf(candidate), live))
    }

    @Test
    fun `the audio switch never lands on another artist's song of the same name`() {
        val bieber = TrackMatcher.Target(title = "Sorry", artist = "Justin Bieber", durationSec = 200)
        val fx = song("FXSORRY0001", "Sorry (Dear. Daddy)", "f(x)", "3:21")
        val other = song("OTHERSORRY1", "Sorry", "Beyonce", "3:20")
        assertNull(TrackMatcher.bestSameArtist(listOf(fx, other), bieber))
        val right = song("BIEBERSORRY", "Sorry", "Justin Bieber", "3:20")
        assertEquals("BIEBERSORRY", TrackMatcher.bestSameArtist(listOf(other, right, fx), bieber)?.videoId)
    }

    @Test
    fun `a cover band carrying the artist's name is not the artist`() {
        val peaches = TrackMatcher.Target(
            title = "Peaches (feat. Daniel Caesar, Giveon)",
            artist = "Justin Bieber",
            durationSec = 198,
        )
        val coverBand = song(
            "COVERBAND01",
            "Peaches (Originally performed by Justin Bieber)",
            "The Justin Bieber Cover Band",
            "3:22",
        )
        assertNull(TrackMatcher.bestSameArtist(listOf(coverBand), peaches))
        val real = song("REALPEACHES", "Peaches (feat. Daniel Caesar, Giveon)", "Justin Bieber, Daniel Caesar, Giveon", "3:18")
        assertEquals("REALPEACHES", TrackMatcher.bestSameArtist(listOf(coverBand, real), peaches)?.videoId)
    }

    /**
     * A music video carries visual intros and outros the audio master does
     * not, so the official song can be minutes shorter. "Born This Way" runs
     * 7:19 as a video and 4:20 as a song; the gap had been refusing it.
     */
    @Test
    fun `a video longer than its song still switches to the song`() {
        val video = TrackMatcher.Target(
            title = "Born This Way (Official Music Video)",
            artist = "Lady Gaga",
            durationSec = 7 * 60 + 19,
            isVideo = true,
            artistId = "UC_ladygaga",
        )
        val audio = song("BORNTHISWAY", "Born This Way", "Lady Gaga", "4:20", "UC_ladygaga")

        assertEquals(audio, TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))
    }

    /** The reverse gap is a different edit, so the ordinary window still rules. */
    @Test
    fun `a song much longer than its video is still refused`() {
        val video = TrackMatcher.Target(
            title = "Somebody",
            artist = "The Artist",
            durationSec = 3 * 60 + 20,
            isVideo = true,
            artistId = "UC_artist",
        )
        val tooLong = song("LONGEDIT", "Somebody", "The Artist", "5:20", "UC_artist")

        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(tooLong), video))
    }

    /**
     * ko-93: Justin Bieber's "Sorry" must not become f(x)'s "Sorry". "f(x)"
     * has no comparable artist words, so an exact title and a close runtime
     * are not enough for the video switch either.
     */
    @Test
    fun `the video switch never takes another artist's same-named song`() {
        val bieber = TrackMatcher.Target(
            title = "Sorry",
            artist = "Justin Bieber",
            durationSec = 200,
            isVideo = true,
            artistId = "UC_bieber",
        )
        val fx = song("FXSORRY0002", "Sorry (Dear. Daddy)", "f(x)", "3:21", "UC_fx")

        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(fx), bieber))
        assertNull(TrackMatcher.bestSameArtist(listOf(fx), bieber))
    }

    /**
     * YouTube credits "She Will Be Loved" to Maroon 5's earlier name, Kara's
     * Flowers. The release id is the fact behind the display names, so it can
     * establish the same act where the names cannot.
     */
    @Test
    fun `an artist alias switches when both rows name the same release`() {
        val video = TrackMatcher.Target(
            title = "She Will Be Loved",
            artist = "Kara's Flowers",
            durationSec = 4 * 60 + 17,
            isVideo = true,
            artistId = "UC_karasflowers",
            albumId = "MPRE_songsaboutjane",
            album = "Songs About Jane",
        )
        val audio = song(
            "SHEWILLBELOVED",
            "She Will Be Loved",
            "Maroon 5",
            "4:17",
            artistId = "UC_maroon5",
            albumId = "MPRE_songsaboutjane",
            album = "Songs About Jane",
        )

        assertEquals(audio, TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))
    }

    /** ...or the artist channel. */
    @Test
    fun `an artist alias switches when both rows name the same artist channel`() {
        val video = TrackMatcher.Target(
            title = "She Will Be Loved",
            artist = "Kara's Flowers",
            durationSec = 4 * 60 + 17,
            isVideo = true,
            artistIds = listOf("UC_maroon5"),
        )
        val audio = song(
            "SHEWILLBELOVED",
            "She Will Be Loved",
            "Maroon 5",
            "4:17",
            artistId = "UC_maroon5",
            artists = listOf(ArtistRef("Maroon 5", "UC_maroon5")),
        )

        assertEquals(audio, TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))
    }

    /** Without such a signal the alias is not guessed: another act's song is not the answer. */
    @Test
    fun `an artist alias without a catalogue signal is refused`() {
        val video = TrackMatcher.Target(
            title = "She Will Be Loved",
            artist = "Kara's Flowers",
            durationSec = 4 * 60 + 17,
            isVideo = true,
        )
        val audio = song("SHEWILLBELOVED", "She Will Be Loved", "Maroon 5", "4:17")

        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))
    }

    @Test
    fun `a cover is still refused by the video switch`() {
        val video = TrackMatcher.Target(
            title = "Born This Way",
            artist = "Lady Gaga",
            durationSec = 7 * 60 + 19,
            isVideo = true,
            artistId = "UC_ladygaga",
        )
        val cover = song(
            "COVERBORN01",
            "Born This Way (Originally performed by Lady Gaga)",
            "The Lady Gaga Cover Band",
            "4:20",
            "UC_coverband",
        )
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(cover), video))
    }

    @Test
    fun `the video runtime window is one-sided`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 439, isVideo = true)
        assertTrue(TrackMatcher.videoRuntimeAllows(video, song("SHORT", "Song", "Artist", "4:20")))
        assertFalse(TrackMatcher.videoRuntimeAllows(video, song("LONG", "Song", "Artist", "9:00")))
    }
}