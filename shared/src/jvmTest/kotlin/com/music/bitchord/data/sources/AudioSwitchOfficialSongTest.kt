package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The audio switch takes the official song and leaves covers, karaoke and other acts alone. */
class AudioSwitchOfficialSongTest {

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

    private val chocolateVideo = TrackMatcher.Target(
        title = "Chocolate",
        artist = "The 1975",
        durationSec = 227,
        isVideo = true,
        artistId = "The1975VEVO",
    )

    /**
     * Catalogues disagree about the leading "The": YouTube Music files the
     * official release under "The 1975", another row may print "1975".
     */
    @Test
    fun `the 1975 official song matches when the catalogue drops the leading article`() {
        val official = song("official1975", "Chocolate", "1975", "3:44", "The 1975 (Deluxe Version)")
        assertEquals(official, TrackMatcher.bestSameArtist(listOf(official), chocolateVideo))
        assertEquals(official, TrackMatcher.bestOfficialAudioForVideo(listOf(official), chocolateVideo))

        val noArticle = chocolateVideo.copy(artist = "1975")
        val officialWithThe = song("officialThe", "Chocolate", "The 1975", "3:44")
        assertEquals(
            officialWithThe,
            TrackMatcher.bestOfficialAudioForVideo(listOf(officialWithThe), noArticle),
        )
    }

    @Test
    fun `chocolate covers karaoke and other artists stay rejected`() {
        val wrong = listOf(
            song("coverString", "Chocolate", "Midnite String Quartet", "3:44"),
            song("coverMarcquelle", "Chocolate", "Marcquelle, Joey Souls & Pariah", "3:43"),
            song(
                "karaokeVampire",
                "Chocolate (Originally Performed by 1975) [Karaoke Version]",
                "Vampire Voices",
                "3:47",
            ),
            song("hotChocolate", "Chocolate", "Hot Chocolate", "4:05"),
        )
        for (candidate in wrong) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), chocolateVideo), candidate.videoId)
            assertNull(
                TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), chocolateVideo),
                candidate.videoId,
            )
        }
    }

    /**
     * The official radio edit prints its feat. credit inside the version
     * bracket. That credit belongs to the artist, so the bracket is the
     * ordinary release and the video's own cut matches it.
     */
    @Test
    fun `get lucky official radio edit matches the video`() {
        val video = TrackMatcher.Target(
            title = "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            artist = "Daft Punk",
            durationSec = 248,
            isVideo = true,
        )
        val radio = song(
            "getLuckyRadio",
            "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)",
            "Daft Punk, Pharrell Williams & Nile Rodgers",
            "4:09",
        )
        assertEquals(radio, TrackMatcher.bestSameArtist(listOf(radio), video))
        assertEquals(radio, TrackMatcher.bestOfficialAudioForVideo(listOf(radio), video))
        assertEquals(
            emptySet(),
            TrackMatcher.parseTitle(radio.title, radio.artist).versions,
        )
    }

    @Test
    fun `get lucky covers karaoke and other artists stay rejected`() {
        val video = TrackMatcher.Target(
            title = "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            artist = "Daft Punk",
            durationSec = 248,
            isVideo = true,
        )
        val wrong = listOf(
            song("heliaCover", "Get Lucky", "HELIA", "4:08"),
            song("littleRiver", "Get Lucky", "Little River Band", "4:15"),
            song(
                "karaokeClara",
                "Get Lucky (Premium Karaoke Version With Background Vocals) " +
                    "(Originally Performed By Daft Punk & Pharrell Williams)",
                "Clara Oaks",
                "4:25",
            ),
        )
        for (candidate in wrong) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), video), candidate.videoId)
            assertNull(
                TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), video),
                candidate.videoId,
            )
        }
    }

    /** The album cut runs two minutes longer than the video: a different edit, still refused. */
    @Test
    fun `a get lucky album cut much longer than the video is still refused`() {
        val video = TrackMatcher.Target(
            title = "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            artist = "Daft Punk",
            durationSec = 248,
            isVideo = true,
        )
        val album = song(
            "getLuckyAlbum",
            "Get Lucky (feat. Pharrell Williams and Nile Rodgers)",
            "Daft Punk, Pharrell Williams & Nile Rodgers",
            "6:10",
            "Random Access Memories",
        )
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(album), video))
    }
}
