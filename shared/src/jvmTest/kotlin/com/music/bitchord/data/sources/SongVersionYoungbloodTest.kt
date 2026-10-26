package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongVersionYoungbloodTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = null,
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = isVideo,
    )

    @Test
    fun `youngblood official video matches its catalogue song`() {
        val video = song(
            "-RJSbO8UZVY",
            "Youngblood (Official Video)",
            "5 Seconds of Summer",
            "3:44",
            isVideo = true,
        )
        val target = TrackMatcher.targetOf(video)
        // The Korean youtube.com fallback credits album audio to the
        // auto-generated Topic channel; that suffix is the channel, not an act.
        for (credit in listOf("5 Seconds of Summer", "5 Seconds of Summer - Topic")) {
            val audio = song("YOUNGBLOOD", "Youngblood", credit, "3:23")
            assertEquals(audio, TrackMatcher.bestSameArtist(listOf(audio), target), "credit=$credit")
            assertEquals(
                audio,
                TrackMatcher.bestOfficialAudioForVideo(listOf(audio), target),
                "credit=$credit",
            )
        }
    }

    @Test
    fun `common video title tails are packaging not identity`() {
        val tails = listOf(
            "(Official Video)",
            "(Official Music Video)",
            "[MV]",
            "M/V",
            "MV",
            "Official MV",
            "Official M/V",
            "(Lyric Video)",
            "(Official Audio)",
        )
        val audio = song("YOUNGBLOOD", "Youngblood", "5 Seconds of Summer", "3:23")
        for (tail in tails) {
            val video = song(
                "-RJSbO8UZVY",
                "Youngblood $tail",
                "5 Seconds of Summer",
                "3:44",
                isVideo = true,
            )
            val target = TrackMatcher.targetOf(video)
            assertEquals(
                audio,
                TrackMatcher.bestOfficialAudioForVideo(listOf(audio), target),
                "tail=$tail",
            )
        }
    }

    @Test
    fun `cover piano and live rows are still refused`() {
        val video = song(
            "-RJSbO8UZVY",
            "Youngblood (Official Video)",
            "5 Seconds of Summer",
            "3:44",
            isVideo = true,
        )
        val target = TrackMatcher.targetOf(video)
        val wrong = listOf(
            song("COVER", "Youngblood", "Some Cover Band", "3:23"),
            song("OTHERTOPIC", "Youngblood", "Another Singer - Topic", "3:23"),
            song("PIANO", "Youngblood (Piano Version)", "5 Seconds of Summer", "3:23"),
            song("LIVE", "Youngblood (Live)", "5 Seconds of Summer", "3:23"),
            song("KARAOKE", "Youngblood (Karaoke Version)", "5 Seconds of Summer", "3:23"),
        )
        for (candidate in wrong) {
            assertNull(
                TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), target),
                "candidate=${candidate.title} / ${candidate.artist}",
            )
        }
    }

    @Test
    fun `a rejected candidate says which check failed`() {
        val video = song(
            "-RJSbO8UZVY",
            "Youngblood (Official Video)",
            "5 Seconds of Summer",
            "3:44",
            isVideo = true,
        )
        val target = TrackMatcher.targetOf(video)
        fun reason(candidate: Song) = TrackMatcher.songVersionRejectReason(candidate, target)

        assertNull(reason(song("OK", "Youngblood", "5 Seconds of Summer", "3:23")))
        assertNull(reason(song("TOPIC", "Youngblood", "5 Seconds of Summer - Topic", "3:23")))
        assertEquals(
            "title",
            reason(song("WRONG", "Amnesia", "5 Seconds of Summer", "3:23")),
        )
        assertEquals(
            "artist",
            reason(song("OTHERSINGER", "Youngblood", "Another Singer", "3:23")),
        )
        assertEquals(
            "cover",
            reason(song("KARAOKE", "Youngblood (Karaoke Version)", "5 Seconds of Summer", "3:23")),
        )
        assertEquals(
            "type",
            reason(
                song(
                    "VIDEO",
                    "Youngblood (Official Video)",
                    "5 Seconds of Summer",
                    "3:44",
                    isVideo = true,
                ),
            ),
        )
        assertEquals(
            "duration 91s",
            reason(song("LONG", "Youngblood", "5 Seconds of Summer", "5:15")),
        )
    }
}