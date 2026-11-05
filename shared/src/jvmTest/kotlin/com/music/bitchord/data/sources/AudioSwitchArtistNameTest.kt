package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Official audio rows whose artist name is a number or a "The ..." act. */
class AudioSwitchArtistNameTest {

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

    private val chocolate = TrackMatcher.Target(
        title = "Chocolate",
        artist = "The 1975",
        durationSec = 171,
        isVideo = true,
    )

    @Test
    fun `the 1975 song is accepted with and without the leading the`() {
        val full = song("CHOCOLATE01", "Chocolate", "The 1975", "2:51")
        val short = song("CHk5SWVO4p8", "Chocolate", "1975", "2:51")
        assertEquals("CHOCOLATE01", TrackMatcher.bestSameArtist(listOf(full), chocolate)?.videoId)
        assertEquals("CHOCOLATE01", TrackMatcher.bestOfficialAudioForVideo(listOf(full), chocolate)?.videoId)
        assertEquals("CHk5SWVO4p8", TrackMatcher.bestSameArtist(listOf(short), chocolate)?.videoId)
        assertEquals("CHk5SWVO4p8", TrackMatcher.bestOfficialAudioForVideo(listOf(short), chocolate)?.videoId)
        assertEquals(
            "CHOCOLATE01",
            TrackMatcher.bestSameArtist(listOf(full), chocolate.copy(artist = "1975"))?.videoId,
        )
        assertEquals(
            "CHk5SWVO4p8",
            TrackMatcher.bestSameArtist(listOf(short), chocolate.copy(artist = "1975"))?.videoId,
        )
        assertNull(TrackMatcher.songVersionRejectReason(short, chocolate))
    }

    @Test
    fun `a rejected video row names the type it was dropped for`() {
        // The youtube.com fallback answers every row with a 16:9 still, so a
        // device log has to separate "the page held a video" from the other
        // refusals; 2026-11-05 the line said only `reason=type`.
        val video = song("CHk5SWVO4p8", "Chocolate", "The 1975", "2:51").copy(isVideo = true)

        assertEquals("type(video)", TrackMatcher.songVersionRejectReason(video, chocolate))
    }

    @Test
    fun `the reported chocolate video accepts an audio-only row under its own id`() {
        val video = song("CHk5SWVO4p8", "Chocolate", "The 1975", "2:51").copy(isVideo = true)
        val audio = song("CHk5SWVO4p8", "Chocolate", "The 1975", "2:51")
        assertTrue(SongVersionPolicy.isSongVersionOf(video, audio))
        // A completed search that found nothing hands the video row back.
        assertFalse(SongVersionPolicy.isSongVersionOf(video, video))
    }

    @Test
    fun `a chocolate cover karaoke or tribute is still refused`() {
        val cover = song("CHOCOVER001", "Chocolate (Cover)", "The 1975", "2:51")
        val karaoke = song("CHOKARAOKE1", "Chocolate (Karaoke Version)", "The 1975", "2:51")
        val tribute = song("CHOTRIBUTE1", "Chocolate", "The 1975 Tribute Band", "2:51")
        // The rows the phone logged as rejected were other acts' uploads of
        // the same title, not the official recording; they must stay refused.
        val reported = listOf(
            song("E6ZZbPB8WSQ", "Chocolate", "Midnite String Quartet - Topic", "2:51"),
            song("x4kijUl0lrM", "Chocolate", "Release - Topic", "2:51"),
            song("q_8rnM4d9HU", "Chocolate", "Logan Walls - Topic", "2:51"),
        )
        for (candidate in listOf(cover, karaoke, tribute) + reported) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), chocolate), candidate.videoId)
            assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), chocolate), candidate.videoId)
        }
    }

    private val getLucky = TrackMatcher.Target(
        title = "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
        artist = "Daft Punk",
        durationSec = 369,
        isVideo = true,
    )

    @Test
    fun `get lucky with its featured credits is accepted`() {
        val plain = song("GETLUCKY001", "Get Lucky", "Daft Punk, Pharrell Williams, Nile Rodgers", "6:09")
        val full = song("GETLUCKY002", "Get Lucky (feat. Pharrell Williams, Nile Rodgers)", "Daft Punk", "6:09")
        val lead = song("GETLUCKY003", "Get Lucky", "Daft Punk", "6:09")
        val andCredits = song(
            "GETLUCKY004",
            "Get Lucky (feat. Pharrell Williams and Nile Rodgers)",
            "Daft Punk feat. Pharrell Williams and Nile Rodgers",
            "6:09",
        )
        for (candidate in listOf(plain, full, lead, andCredits)) {
            assertEquals(candidate.videoId, TrackMatcher.bestSameArtist(listOf(candidate), getLucky)?.videoId)
            assertEquals(candidate.videoId, TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), getLucky)?.videoId)
            assertNull(TrackMatcher.songVersionRejectReason(candidate, getLucky))
        }
    }

    @Test
    fun `a get lucky cover karaoke or other artist is still refused`() {
        val cover = song("GETCOVER001", "Get Lucky (Cover)", "Daft Punk", "6:09")
        val karaoke = song("GETKARAOKE1", "Get Lucky (Karaoke Version)", "Daft Punk", "6:09")
        val other = song("GETOTHER001", "Get Lucky", "Pharrell Williams", "6:09")
        for (candidate in listOf(cover, karaoke, other)) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), getLucky), candidate.videoId)
            assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), getLucky), candidate.videoId)
        }
    }

    @Test
    fun `get lucky credited only to its featured performer is accepted`() {
        val row = song(
            "imDKnNvqpq0",
            "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            "Daft Punk",
            "6:09",
        )
        val target = TrackMatcher.audioSwitchTargetOf(row)
        val featured = song(
            "GETFEATURED1",
            "Get Lucky (feat. Pharrell Williams, Nile Rodgers)",
            "Pharrell Williams",
            "6:09",
        )
        assertEquals(featured.videoId, TrackMatcher.bestSameArtist(listOf(featured), target)?.videoId)
        assertEquals(
            featured.videoId,
            TrackMatcher.bestOfficialAudioForVideo(listOf(featured), target)?.videoId,
        )
        assertNull(TrackMatcher.songVersionRejectReason(featured, target))
        // The title's feature does not admit a cover or an unrelated act.
        val cover = song("GETFEATCOVER", "Get Lucky", "The Cover Crew", "6:09")
        val other = song("GETFEATOTHER", "Get Lucky", "Another Artist", "6:09")
        // The row the phone logged as rejected was HELIA's cover, not the song.
        val reportedCover = song("imDKnNvqpq0", "Get Lucky", "HELIA - Topic", "6:09")
        for (candidate in listOf(cover, other, reportedCover)) {
            assertNull(TrackMatcher.bestSameArtist(listOf(candidate), target), candidate.videoId)
            assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(candidate), target), candidate.videoId)
        }
    }

    // ---- Title bridge (ILLIT "You're Under Arrest" / 체포하겠어) --------------

    private val arrestVideo = Song(
        videoId = "NPZtKA6343o",
        title = "ILLIT (아일릿) \"You're Under Arrest\" Official MV",
        artist = "HYPE UNIVERSE",
        thumbnailUrl = null,
        durationText = "3:12",
        isVideo = true,
    )

    /** The fanchant upload: the one row that prints both names of the song. */
    private fun arrestBridge() = Song(
        videoId = "V2V9-9VhYJo",
        title = "ILLIT (아일릿) '체포하겠어 (You're Under Arrest)'",
        artist = "ILLIT",
        thumbnailUrl = null,
        durationText = "3:12",
        isVideo = true,
    )

    /** The album row on BREAK EVEN, filed under the Korean title only. */
    private fun arrestAudio() = Song(
        videoId = "qsqvJpOgrWw",
        title = "체포하겠어",
        artist = "ILLIT",
        thumbnailUrl = null,
        durationText = "3:05",
    )

    @Test
    fun `a bracketed translation is a second reading of the title`() {
        assertEquals(
            listOf("youreunderarrest"),
            TrackMatcher.bracketReadings("체포하겠어 (You're Under Arrest)"),
        )
        // A take and packaging never become a second reading of the name.
        for (title in listOf(
            "체포하겠어 (Live)",
            "체포하겠어 (Remix)",
            "체포하겠어 (Official Video)",
            "체포하겠어 (Feat. X)",
        )) {
            assertTrue(TrackMatcher.bracketReadings(title).isEmpty(), title)
        }
        // The reading is scoped to the bridge: the title parser and the search
        // queries keep treating a bracketed aside as packaging
        // (see TitleNormalizationTest for the query list).
        assertEquals(
            listOf("체포하겠어"),
            TrackMatcher.parseTitle("체포하겠어 (You're Under Arrest)").cores(),
        )
    }

    @Test
    fun `a page row that prints both names bridges the korean release title`() {
        val target = TrackMatcher.audioSwitchTargetOf(arrestVideo)
        val audio = arrestAudio()
        // The ordinary gates still cannot compare the two titles.
        assertNull(TrackMatcher.bestSameArtist(listOf(audio), target))
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(audio), target))
        assertEquals("title", TrackMatcher.songVersionRejectReason(audio, target))

        assertEquals(
            audio to "youreunderarrest",
            TrackMatcher.bestTitleBridged(listOf(arrestBridge(), audio), listOf(audio), target),
        )
    }

    @Test
    fun `without the bridging row the other name is never guessed at`() {
        val target = TrackMatcher.audioSwitchTargetOf(arrestVideo)
        val audio = arrestAudio()
        assertNull(TrackMatcher.bestTitleBridged(listOf(audio), listOf(audio), target))
        // A bridge of another act proves nothing about this song.
        val foreign = arrestBridge().copy(artist = "Another Singer")
        assertNull(TrackMatcher.bestTitleBridged(listOf(foreign, audio), listOf(audio), target))
    }

    @Test
    fun `a title bridge still refuses another act a cover a take and another runtime`() {
        val target = TrackMatcher.audioSwitchTargetOf(arrestVideo)
        val bridge = arrestBridge()
        val wrong = listOf(
            arrestAudio().copy(videoId = "OTHERACT001", artist = "Another Singer"),
            arrestAudio().copy(videoId = "COVER000001", title = "체포하겠어 (Cover)"),
            arrestAudio().copy(videoId = "LIVE0000001", title = "체포하겠어 (Live)"),
            arrestAudio().copy(videoId = "REMIX000001", title = "체포하겠어 (Remix)"),
            arrestAudio().copy(videoId = "LONG0000001", durationText = "6:40"),
            arrestAudio().copy(videoId = "VIDEO000001", isVideo = true),
        )
        for (candidate in wrong) {
            assertNull(
                TrackMatcher.bestTitleBridged(listOf(bridge, candidate), listOf(candidate), target),
                candidate.videoId,
            )
        }
    }
}
