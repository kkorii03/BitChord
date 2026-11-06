package com.music.bitchord.data.sources

import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.ko.KoAudioSwitch
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

    /**
     * Phone 11-06: the shared link's row reached the song version search with
     * the uploader as its artist, so every query asked for the channel and no
     * catalogue row could answer. The query is the title and the credit the
     * title states.
     */
    @Test
    fun `the song version query names the artist, never the channel`() {
        val row = YouTubeDisplayCleaner.clean(arrestVideo)
        assertEquals("ILLIT", row.artist)

        val queries = TrackMatcher.audioSwitchQueries(row)
        // Queries are normalised to lower case before they are sent.
        assertTrue(queries.any { it.contains("ILLIT", ignoreCase = true) }, queries.toString())
        assertTrue(queries.none { it.contains("HYPE UNIVERSE", ignoreCase = true) }, queries.toString())
        assertTrue(queries.none { it.contains("Official MV", ignoreCase = true) }, queries.toString())
    }

    @Test
    fun `an upload on the artist's own channel keeps its artist in the query`() {
        val row = YouTubeDisplayCleaner.clean(
            Song(
                videoId = "UPTOWN00001",
                title = "Uptown Funk (Official Video)",
                artist = "Mark Ronson",
                thumbnailUrl = null,
            ),
        )

        assertEquals("Mark Ronson", row.artist)
        val queries = TrackMatcher.audioSwitchQueries(row)
        assertTrue(queries.any { it.contains("Mark Ronson", ignoreCase = true) }, queries.toString())
    }

    /**
     * Phone 11-06, vc148: the deep-linked row knew its release, and
     * [YouTubeDisplayCleaner.cleanUpload] reads an album id as "already
     * display-ready" and skips — so the search still asked for the channel
     * while the player showed the artist.
     */
    @Test
    fun `a deep-linked row that knows its release is still searched by its artist`() {
        val deepLink = arrestVideo.copy(albumId = "MPREb_breakEven", albumName = "BREAK EVEN")

        val row = KoAudioSwitch.audioSwitchRow(deepLink)

        assertEquals("ILLIT", row.artist)
        assertEquals("You're Under Arrest", row.title)
        // The release is what the match compares, so it stays on the row.
        assertEquals("MPREb_breakEven", row.albumId)
        val queries = TrackMatcher.audioSwitchQueries(row)
        assertTrue(queries.any { it.contains("ILLIT", ignoreCase = true) }, queries.toString())
        assertTrue(queries.none { it.contains("HYPE UNIVERSE", ignoreCase = true) }, queries.toString())
    }

    /**
     * `audio switch: no official song match for '…' by '…'` is written from
     * this row, and it printed the uploader while the search used the artist.
     */
    @Test
    fun `the miss line names the artist the search used`() {
        val row = KoAudioSwitch.audioSwitchRow(arrestVideo.copy(albumId = "MPREb_breakEven"))

        assertEquals("You're Under Arrest", row.title)
        assertEquals("ILLIT", row.artist)
    }

    @Test
    fun `a cover and an artists own channel are left as they are`() {
        val cover = Song(
            videoId = "COVER000001",
            title = "Shape of You - Ed Sheeran (cover by Jane)",
            artist = "Jane",
            thumbnailUrl = null,
            isVideo = true,
        )
        // A cover's own title is what the switch must refuse, not the song it covers.
        assertEquals(cover, KoAudioSwitch.audioSwitchRow(cover))

        val own = Song(
            videoId = "UPTOWN00002",
            title = "Uptown Funk (Official Video)",
            artist = "Mark Ronson",
            thumbnailUrl = null,
            isVideo = true,
        )
        assertEquals("Mark Ronson", KoAudioSwitch.audioSwitchRow(own).artist)

        val topic = Song(
            videoId = "TOPIC000001",
            title = "체포하겠어",
            artist = "ILLIT - Topic",
            thumbnailUrl = null,
        )
        assertEquals("ILLIT", KoAudioSwitch.audioSwitchRow(topic).artist)
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

    // ---- The other-script grade the switch adopts (phone 11-06) -------------

    private fun arrestTarget() =
        TrackMatcher.audioSwitchTargetOf(YouTubeDisplayCleaner.cleanUpload(arrestVideo))

    /**
     * The 11-06 log: `reject qsqvJpOgrWw 체포하겠어 reason=title` and then no
     * match at all, because that page held no row printing both names. The
     * credit and the runtime are what is left, and the quality upgrade already
     * trusts exactly those two for this song.
     */
    @Test
    fun `a korean album row of the same act and length is adopted without a bridging row`() {
        val target = arrestTarget()
        val audio = arrestAudio().copy(durationText = "3:02")
        // The exact-title gates still cannot compare the two names...
        assertNull(TrackMatcher.bestSameArtist(listOf(audio), target))
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(audio), target))
        // ...the page holds no row that prints both, so the bridge is out too...
        assertNull(TrackMatcher.bestTitleBridged(listOf(audio), listOf(audio), target))
        // ...and the credit plus the runtime still carry it.
        assertTrue(TrackMatcher.isAudioSwitchTitleUnverified(audio, target))
        assertNull(TrackMatcher.songVersionRejectReason(audio, target))
        // The three-second window of the upgrade grade is the one thing that
        // does not carry over: a video's runtime is visual timing.
        assertFalse(TrackMatcher.isTitleUnverifiedOriginal(audio, target))
    }

    /**
     * The credit and the runtime are all this grade has, so a different song by
     * the same act must not pass on a runtime that is merely in the same
     * ballpark. Ninety seconds let any of its songs through (review 11-06).
     */
    @Test
    fun `another song of the same act outside a video's overhang is not adopted`() {
        val target = arrestTarget() // 3:12
        val tooShort = arrestAudio().copy(videoId = "SHORTER0001", title = "다른 노래", durationText = "2:30")
        val tooLong = arrestAudio().copy(videoId = "LONGER00001", title = "다른 노래", durationText = "3:20")
        assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(tooShort, target))
        assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(tooLong, target))
    }

    @Test
    fun `two differently titled rows of the act cannot name the video's song`() {
        val target = arrestTarget()
        val one = arrestAudio()
        val other = arrestAudio().copy(videoId = "OTHERSONG01", title = "빌려온 고양이", durationText = "3:06")
        assertNull(TrackMatcher.titleUnverifiedAdoption(listOf(one, other), target))
    }

    @Test
    fun `copies of one title are still one song and the nearest runtime wins`() {
        val target = arrestTarget()
        val album = arrestAudio().copy(videoId = "ALBUMCOPY01", durationText = "3:00")
        val single = arrestAudio().copy(videoId = "SINGLECOPY1", durationText = "3:05")
        assertEquals("SINGLECOPY1", TrackMatcher.titleUnverifiedAdoption(listOf(album, single), target)?.videoId)
    }

    @Test
    fun `the phone's page with the one korean row still adopts it`() {
        assertEquals("qsqvJpOgrWw", TrackMatcher.titleUnverifiedAdoption(listOf(arrestAudio()), arrestTarget())?.videoId)
    }

    @Test
    fun `the same korean title filed by another act is never adopted`() {
        val target = arrestTarget()
        val other = arrestAudio().copy(videoId = "OTHERACT001", artist = "Another Singer")
        assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(other, target))
        assertEquals("title", TrackMatcher.songVersionRejectReason(other, target))
    }

    @Test
    fun `a korean row outside the video runtime window is not adopted`() {
        val target = arrestTarget()
        // Longer than the video by more than the video switch's own limit.
        val long = arrestAudio().copy(videoId = "LONG0000001", durationText = "6:40")
        assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(long, target))
        assertEquals("title", TrackMatcher.songVersionRejectReason(long, target))
        // No runtime at all is no evidence either.
        val unknown = arrestAudio().copy(videoId = "NORUNTIME01", durationText = null)
        assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(unknown, target))
    }

    @Test
    fun `a cover karaoke remix or instrumental of the korean title is still refused`() {
        val target = arrestTarget()
        for (candidate in listOf(
            arrestAudio().copy(videoId = "COVER000001", title = "체포하겠어 (Cover)"),
            arrestAudio().copy(videoId = "KARAOKE0001", title = "체포하겠어 (Karaoke Version)"),
            arrestAudio().copy(videoId = "REMIX000001", title = "체포하겠어 (Remix)"),
            arrestAudio().copy(
                videoId = "INSTR000001",
                albumName = "체포하겠어 (Instrumental Edition)",
            ),
        )) {
            assertFalse(TrackMatcher.isAudioSwitchTitleUnverified(candidate, target), candidate.videoId)
        }
    }

    /**
     * The post-check of the swap: the lyrics lookup has already judged what the
     * adopted row sings, and two providers' sheets failing the same sound is
     * the sound. That verdict undoes the swap; no request or decode is made to
     * find out.
     */
    @Test
    fun `a swap whose sound two sheets failed to describe is undone`() {
        val swapped = arrestAudio().copy(videoId = "WRONGAUDIO1")
        val verdict = LyricsMismatch.Verdict(
            heardWords = 132,
            matchedLines = 1,
            totalLines = 24,
            rejected = true,
        )
        LyricsMismatch.rememberRejected(swapped.videoId, LyricsSource.LRCLIB, emptyList(), verdict)
        assertNull(
            LyricsMismatch.wrongSoundNote(swapped.videoId),
            "one provider's wrong sheet is a wrong sheet",
        )
        LyricsMismatch.rememberRejected(swapped.videoId, LyricsSource.KUGOU, emptyList(), verdict)
        assertEquals(
            "audio switch: revert WRONGAUDIO1 (lyrics mismatch 1/24)",
            LyricsMismatch.wrongSoundNote(swapped.videoId),
        )
        // A sound that was never judged is left alone.
        assertNull(LyricsMismatch.wrongSoundNote("NEVERJUDGED"))
    }

    @Test
    fun `a title bridge refuses a row far shorter than the video`() {
        val target = TrackMatcher.audioSwitchTargetOf(arrestVideo)
        // 3:12 video, 0:35 row: a teaser or clip, not the song.
        val teaser = arrestAudio().copy(videoId = "TEASER00001", durationText = "0:35")
        assertNull(TrackMatcher.bestTitleBridged(listOf(arrestBridge(), teaser), listOf(teaser), target))
    }

    @Test
    fun `a title bridge picks the best row not the first`() {
        val target = TrackMatcher.audioSwitchTargetOf(arrestVideo)
        val far = arrestAudio().copy(videoId = "FAR00000001", durationText = "2:10")
        val close = arrestAudio()
        assertEquals(
            close to "youreunderarrest",
            TrackMatcher.bestTitleBridged(listOf(arrestBridge(), far, close), listOf(far, close), target),
        )
    }

    @Test
    fun `a row the title bridge accepts is not logged as a title miss`() {
        val target = TrackMatcher.audioSwitchTargetOf(arrestVideo)
        val audio = arrestAudio()
        val page = listOf(arrestBridge(), audio)
        assertNull(TrackMatcher.songVersionRejectReason(audio, target, page))
        // Without the bridge on the page it is still the honest title miss.
        assertEquals("title", TrackMatcher.songVersionRejectReason(audio, target, listOf(audio)))
    }
}
