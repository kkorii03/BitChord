package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Official audio for an MV whose credit differs only in packaging or a feature. */
class AudioSwitchArtistCreditTest {

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

    @Test
    fun `the audio switch accepts The 1975 when the catalogue drops the article`() {
        val video = TrackMatcher.Target("Chocolate", "The 1975", durationSec = 227, isVideo = true)
        val official = song(
            "-bxNzEtGjZs",
            "Chocolate",
            "1975",
            "3:45",
            "The 1975 (Deluxe Version)",
        )

        assertTrue(TrackMatcher.namesTheSameArtist("The 1975", "1975"))
        assertEquals(official, TrackMatcher.bestSameArtist(listOf(official), video))
        assertEquals(official, TrackMatcher.bestOfficialAudioForVideo(listOf(official), video))
    }

    @Test
    fun `the audio switch still refuses The 1975 covers karaoke and another artist`() {
        val video = TrackMatcher.Target("Chocolate", "The 1975", durationSec = 227, isVideo = true)
        val cover = song(
            "E6ZZbPB8WSQ",
            "Chocolate",
            "Midnite String Quartet",
            "3:57",
            "MSQ Performs The 1975",
        )
        val karaoke = song(
            "H7Y5Y_Etaj0",
            "Chocolate (Originally Performed by the 1975) [Karaoke Version]",
            "Kids Biz",
            "3:47",
            "Everything But the Voice!",
        )
        val other = song("HOTCHOCOLATE", "Chocolate", "Hot Chocolate", "3:45")

        for (candidate in listOf(cover, karaoke, other)) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), video), candidate.videoId)
            assertNull(
                TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), video),
                candidate.videoId,
            )
        }
    }

    @Test
    fun `the audio switch accepts Get Lucky's official radio edit with its feature credit`() {
        val video = TrackMatcher.Target(
            "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            "Daft Punk",
            durationSec = 248,
            isVideo = true,
        )
        val radioEdit = song(
            "Rgrt_8mXrK8",
            "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)",
            "Daft Punk, Pharrell Williams, Nile Rodgers",
            "4:09",
        )

        assertEquals(
            emptySet<String>(),
            TrackMatcher.parseTitle(radioEdit.title, radioEdit.artist).versions,
        )
        assertEquals(radioEdit, TrackMatcher.bestSameArtist(listOf(radioEdit), video))
        assertEquals(
            radioEdit,
            TrackMatcher.bestOfficialAudioForVideo(listOf(radioEdit), video),
        )
    }

    @Test
    fun `the audio switch accepts Get Lucky's album release with its feature credit`() {
        val video = TrackMatcher.Target(
            "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            "Daft Punk",
            isVideo = true,
        )
        val album = song(
            "4D7u5KF7SP8",
            "Get Lucky (feat. Pharrell Williams and Nile Rodgers)",
            "Daft Punk, Pharrell Williams, Nile Rodgers",
            "6:10",
            "Random Access Memories",
        )

        assertEquals(album, TrackMatcher.bestSameArtist(listOf(album), video))
        assertEquals(album, TrackMatcher.bestOfficialAudioForVideo(listOf(album), video))
    }

    @Test
    fun `the audio switch still refuses Get Lucky covers karaoke and another artist`() {
        val video = TrackMatcher.Target(
            "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            "Daft Punk",
            isVideo = true,
        )
        val cover = song("imDKnNvqpq0", "Get Lucky", "HELIA", "4:08")
        val karaoke = song(
            "GoJDUy3lz1M",
            "Get Lucky (Karaoke Version) (Originally Performed By Daft Punk & Pharrell Williams)",
            "Clara Oaks",
            "4:22",
        )
        val other = song(
            "GTFVPIzSIGA",
            "Get Lucky (feat. Charles Butler)",
            "Beats Antique",
            "3:43",
        )

        for (candidate in listOf(cover, karaoke, other)) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), video), candidate.videoId)
            assertNull(
                TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), video),
                candidate.videoId,
            )
        }
    }
}
