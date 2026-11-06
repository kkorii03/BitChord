package com.music.bitchord.data.sources

import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoAudioSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A channel name that reached the artist field: `Artist Official`,
 * `Artist official`, `한글 ENGLISH`, and the same in the other order. The
 * comparison has to read the artist out of it; the display string stays as
 * the uploader wrote it.
 */
class ArtistChannelSuffixTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = "3:20",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    @Test
    fun `an official channel suffix is not part of the artist`() {
        assertTrue(TrackMatcher.namesTheSameArtist("TAEYEON Official", "TAEYEON"))
        assertTrue(TrackMatcher.namesTheSameArtist("Heize official", "Heize"))
        assertTrue(TrackMatcher.namesTheSameArtist("TAEYEON Official Channel", "TAEYEON"))
        assertTrue(TrackMatcher.namesTheSameArtist("TAEYEONVEVO", "TAEYEON"))
    }

    @Test
    fun `a korean and english credit matches either spelling`() {
        assertTrue(TrackMatcher.namesTheSameArtist("다비치 DAVICHI", "다비치"))
        assertTrue(TrackMatcher.namesTheSameArtist("다비치 DAVICHI", "DAVICHI"))
        assertTrue(TrackMatcher.namesTheSameArtist("이무진 LEE MU JIN", "이무진"))
        assertTrue(TrackMatcher.namesTheSameArtist("이무진 LEE MU JIN", "LEE MU JIN"))
    }

    @Test
    fun `the audio switch accepts the official row for each channel spelling`() {
        val cases = listOf(
            Triple("TAEYEON Official", "TAEYEON", "INVU"),
            Triple("Heize official", "Heize", "비도 오고 그래서"),
            Triple("다비치 DAVICHI", "다비치", "이 사랑"),
            Triple("이무진 LEE MU JIN", "이무진", "신호등"),
        )
        for ((rowArtist, candidateArtist, title) in cases) {
            val target = TrackMatcher.Target(
                title = title,
                artist = rowArtist,
                durationSec = 200,
                isVideo = true,
            )
            val candidate = song("CANDIDATE01", title, candidateArtist)
            assertEquals(
                candidate,
                TrackMatcher.bestSameArtist(listOf(candidate), target),
                rowArtist,
            )
            assertEquals(
                candidate,
                TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), target),
                rowArtist,
            )
        }
    }

    @Test
    fun `official does not make different singers one artist`() {
        assertFalse(TrackMatcher.namesTheSameArtist("TAEYEON Official", "Heize"))
        assertFalse(TrackMatcher.namesTheSameArtist("Official", "TAEYEON"))
        assertFalse(TrackMatcher.namesTheSameArtist("Official Channel", "TAEYEON"))
        assertFalse(TrackMatcher.namesTheSameArtist("Official Heize", "Heize"))
    }

    @Test
    fun `a cover channel is not the original artist`() {
        assertFalse(TrackMatcher.namesTheSameArtist("다비치 DAVICHI", "다비치 Cover"))
        assertFalse(TrackMatcher.namesTheSameArtist("다비치 DAVICHI", "Jane Cover"))
        assertFalse(TrackMatcher.namesTheSameArtist("Heize", "Heize Cover"))

        val target = TrackMatcher.Target("이 사랑", "다비치 DAVICHI", 200, isVideo = true)
        val cover = song("COVER00001", "이 사랑", "다비치 Cover")
        assertNull(TrackMatcher.bestSameArtist(listOf(cover), target))
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(cover), target))
    }

    @Test
    fun `an episode row is not a song`() {
        val target = TrackMatcher.Target("INVU", "TAEYEON Official", 200, isVideo = true)
        val episode = song("EPISODE001", "Episode 5", "TAEYEON")
        assertNull(TrackMatcher.bestSameArtist(listOf(episode), target))
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(episode), target))
    }

    @Test
    fun `the miss line names the video id`() {
        val row = song("VIDEO12345", "Some Song", "TAEYEON Official")
        assertEquals(
            "audio switch: no official song match videoId=VIDEO12345 " +
                "for 'Some Song' by 'TAEYEON Official'",
            KoAudioSwitch.noMatchLine(row, ""),
        )
    }

    @Test
    fun `the display strings are left as the uploader wrote them`() {
        for (artist in listOf("TAEYEON Official", "Heize official", "다비치 DAVICHI", "이무진 LEE MU JIN")) {
            val row = song("DISPLAY001", "Some Song", artist)
            assertEquals(artist, YouTubeDisplayCleaner.clean(row).artist, artist)
            assertEquals(artist, KoAudioSwitch.audioSwitchRow(row).artist, artist)
        }
    }
}
