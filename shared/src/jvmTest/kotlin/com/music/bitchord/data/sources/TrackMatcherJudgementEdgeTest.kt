package com.music.bitchord.data.sources

import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching judgements that run without Android: scoring, the substitute
 * gate, cover detection, the video switch, runtime windows and catalogue
 * collisions.
 */
class TrackMatcherJudgementEdgeTest {

    private fun song(
        id: String = "id",
        title: String,
        artist: String,
        duration: String? = null,
        album: String? = null,
        albumId: String? = null,
        artistId: String? = null,
        artists: List<ArtistRef> = emptyList(),
        isExplicit: Boolean? = null,
        isVideo: Boolean = false,
        originalTitle: String? = null,
        originalArtist: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        albumName = album,
        albumId = albumId,
        artistId = artistId,
        artists = artists,
        isExplicit = isExplicit,
        isVideo = isVideo,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
    )

    // ── Target ──────────────────────────────────────────────────────────────

    @Test
    fun `targetOf carries every part of the queue row`() {
        val target = TrackMatcher.targetOf(
            song(
                title = "Song",
                artist = "Artist",
                duration = "3:41",
                album = "Album",
                albumId = "album-1",
                artistId = "UC1",
                artists = listOf(
                    ArtistRef("Artist", "UC1"),
                    ArtistRef("Other", "UC2"),
                    ArtistRef("No Link", null),
                    ArtistRef("Blank", " "),
                ),
                isExplicit = true,
                isVideo = true,
            ),
        )
        assertEquals("Song", target.title)
        assertEquals("Artist", target.artist)
        assertEquals(221, target.durationSec)
        assertEquals("Album", target.album)
        assertEquals("album-1", target.albumId)
        assertEquals("UC1", target.artistId)
        assertEquals(listOf("UC1", "UC2"), target.artistIds)
        assertEquals(true, target.isExplicit)
        assertTrue(target.isVideo)
    }

    @Test
    fun `a cover is matched as its original upload`() {
        val target = TrackMatcher.targetOf(
            song(
                title = "곡명",
                artist = "원곡자 · 부른사람",
                duration = "not a time",
                originalTitle = "Original Upload",
                originalArtist = "Uploader",
            ),
        )
        assertEquals("Original Upload", target.title)
        assertEquals("Uploader", target.artist)
        assertNull(target.durationSec)
    }

    // ── Score ───────────────────────────────────────────────────────────────

    private val base = TrackMatcher.Target("Song", "Artist", durationSec = 200)

    @Test
    fun `score adds the exact credit and a tight runtime`() {
        assertEquals(165, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "3:20"), base))
        assertEquals(150, TrackMatcher.score(song(title = "Song", artist = "Artist, Other", duration = "3:20"), base))
        assertEquals(140, TrackMatcher.score(song(title = "Song", artist = "", duration = "3:20"), base))
        assertEquals(110, TrackMatcher.score(song(title = "Song", artist = "Other", duration = "3:20"), base))
    }

    @Test
    fun `an exact runtime may stand in for a missing shared credit only within two seconds`() {
        val different = TrackMatcher.Target("Song", "Artist", durationSec = 200)
        assertEquals(110, TrackMatcher.score(song(title = "Song", artist = "Other", duration = "3:20"), different))
        assertEquals(110, TrackMatcher.score(song(title = "Song", artist = "Other", duration = "3:22"), different))
        assertNull(TrackMatcher.score(song(title = "Song", artist = "Other", duration = "3:23"), different))
        assertNull(TrackMatcher.score(song(title = "Song", artist = "Other", duration = null), different))
        assertNull(
            TrackMatcher.score(
                song(title = "Song", artist = "Other", duration = "3:20"),
                different.copy(isVideo = true),
            ),
        )
    }

    @Test
    fun `duration boundaries are tight then loose then the wider credited window`() {
        assertEquals(165, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "3:23"), base))
        assertEquals(140, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "3:24"), base))
        assertEquals(140, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "3:50"), base))
        // A shared credit lets the runtime drift out to the video window.
        assertEquals(125, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "3:51"), base))
        assertEquals(125, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "4:50"), base))
        assertNull(TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "4:51"), base))
    }

    @Test
    fun `a shared credit widens the runtime window to the video limit`() {
        assertEquals(125, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "4:20"), base))
        assertEquals(125, TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "4:50"), base))
        assertNull(TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "4:51"), base))
        // Without a shared credit the wide window never applies.
        assertNull(TrackMatcher.score(song(title = "Song", artist = "Other", duration = "4:20"), base))
    }

    @Test
    fun `a missing runtime on either side is not a disagreement`() {
        assertEquals(
            125,
            TrackMatcher.score(song(title = "Song", artist = "Artist", duration = null), base),
        )
        assertEquals(
            125,
            TrackMatcher.score(
                song(title = "Song", artist = "Artist", duration = "9:99"),
                base.copy(durationSec = null),
            ),
        )
    }

    @Test
    fun `album context and explicit agreement add their weights`() {
        val rich = TrackMatcher.Target(
            "Song (From Film)",
            "Artist",
            durationSec = 200,
            album = "Release One",
            isExplicit = true,
        )
        val everything = song(
            title = "Song (From Film)",
            artist = "Artist",
            duration = "3:20",
            album = "Release One",
            isExplicit = true,
        )
        assertEquals(240, TrackMatcher.score(everything, rich))
        assertEquals(
            165,
            TrackMatcher.score(song(title = "Song", artist = "Artist", duration = "3:20"), rich),
        )
    }

    @Test
    fun `an explicit disagreement is refused and an unknown flag is neutral`() {
        val explicit = base.copy(isExplicit = true)
        assertNull(
            TrackMatcher.score(
                song(title = "Song", artist = "Artist", duration = "3:20", isExplicit = false),
                explicit,
            ),
        )
        assertEquals(
            165,
            TrackMatcher.score(
                song(title = "Song", artist = "Artist", duration = "3:20", isExplicit = null),
                explicit,
            ),
        )
        assertEquals(
            165,
            TrackMatcher.score(
                song(title = "Song", artist = "Artist", duration = "3:20", isExplicit = true),
                base.copy(isExplicit = null),
            ),
        )
    }

    @Test
    fun `a shared release or artist channel vouches for a different display name`() {
        val byRelease = TrackMatcher.Target("Song", "Artist", durationSec = 200, albumId = "album-1")
        assertEquals(
            150,
            TrackMatcher.score(
                song(title = "Song", artist = "Other", duration = "3:20", albumId = "album-1"),
                byRelease,
            ),
        )
        val byChannel = TrackMatcher.Target("Song", "Artist", durationSec = 200, artistId = "UC1")
        assertEquals(
            150,
            TrackMatcher.score(
                song(title = "Song", artist = "Other", duration = "3:20", artistId = "UC1"),
                byChannel,
            ),
        )
    }

    @Test
    fun `ranked drops different-credit rows whenever a credited row exists`() {
        val target = TrackMatcher.Target(
            "Song (From Film)",
            "Artist",
            durationSec = 200,
            album = "Album",
            isExplicit = true,
        )
        val credited = song(title = "Song", artist = "Artist, Other", duration = "3:20")
        val richerDifferent = song(
            title = "Song (From Film)",
            artist = "Other",
            duration = "3:20",
            album = "Album",
            isExplicit = true,
        )
        assertEquals(
            listOf(credited),
            TrackMatcher.ranked(listOf(richerDifferent, credited), target),
        )
        // With no credited row at all, a runtime-vouched row is still kept.
        assertEquals(
            listOf(richerDifferent),
            TrackMatcher.ranked(listOf(richerDifferent), target.copy(album = null, isExplicit = null)),
        )
    }

    @Test
    fun `ranked orders by confidence`() {
        val close = song("close", "Song", "Artist", "3:21")
        val far = song("far", "Song", "Artist", "3:40")
        val shared = song("shared", "Song", "Artist, Other", "3:20")
        assertEquals(
            listOf("close", "shared", "far"),
            TrackMatcher.ranked(listOf(far, shared, close), base).map { it.videoId },
        )
    }

    @Test
    fun `best is the top of the ranked list and null when nothing matches`() {
        val close = song("close", "Song", "Artist", "3:21")
        val far = song("far", "Song", "Artist", "3:40")
        assertEquals(close, TrackMatcher.best(listOf(far, close), base))
        assertNull(TrackMatcher.best(listOf(song("other", "Other", "Artist", "3:20")), base))
    }

    @Test
    fun `bestSameArtist accepts a partial credit and a channel alias`() {
        val partial = TrackMatcher.Target("Song", "Atif Aslam", durationSec = 200)
        val duet = song("duet", "Song", "Atif Aslam, Tulsi Kumar", "3:20")
        assertEquals(duet, TrackMatcher.bestSameArtist(listOf(duet), partial))

        val alias = TrackMatcher.Target("Song", "Kara's Flowers", durationSec = 200, artistId = "UC1")
        val current = song("current", "Song", "Maroon 5", "3:20", artistId = "UC1")
        assertEquals(current, TrackMatcher.bestSameArtist(listOf(current), alias))
    }

    @Test
    fun `bestSameArtist accepts a shared release but not a different artist`() {
        val byRelease = TrackMatcher.Target("Song", "Artist", durationSec = 200, albumId = "album-1")
        val alias = song("alias", "Song", "Other", "3:20", albumId = "album-1")
        assertEquals(alias, TrackMatcher.bestSameArtist(listOf(alias), byRelease))

        val different = song("different", "Song", "Other", "3:20")
        assertNull(TrackMatcher.bestSameArtist(listOf(different), base))
    }

    @Test
    fun `bestSameArtist refuses a non-comparable name and a cover marker`() {
        // "f(x)" normalises to the comparable word "fx", so the same printed
        // name is the same artist; another artist still is not.
        val fx = TrackMatcher.Target("Sorry", "f(x)", durationSec = 200)
        assertNotNull(TrackMatcher.bestSameArtist(listOf(song("fx", "Sorry", "f(x)", "3:20")), fx))
        assertNull(
            TrackMatcher.bestSameArtist(
                listOf(song("bieber", "Sorry", "Justin Bieber", "3:20")),
                fx,
            ),
        )

        val cover = song(
            "cover",
            "Song (Originally performed by Artist)",
            "Artist",
            "3:20",
        )
        assertNull(TrackMatcher.bestSameArtist(listOf(cover), base))
    }

    // ── Runtime ─────────────────────────────────────────────────────────────

    @Test
    fun `secondsOf reads whole seconds and rejects malformed shapes`() {
        assertEquals(221, TrackMatcher.secondsOf("3:41"))
        assertEquals(3_723, TrackMatcher.secondsOf("1:02:03"))
        assertEquals(221, TrackMatcher.secondsOf(" 3 : 41 "))
        assertEquals(1, TrackMatcher.secondsOf("0:01"))
        assertEquals(3_599, TrackMatcher.secondsOf("59:59"))
        assertEquals(3_600, TrackMatcher.secondsOf("1:00:00"))
        assertEquals(13_260, TrackMatcher.secondsOf("3:41:00"))
        assertEquals(3_723, TrackMatcher.secondsOf("1:2:3"))
        assertNull(TrackMatcher.secondsOf(null))
        assertNull(TrackMatcher.secondsOf(""))
        assertNull(TrackMatcher.secondsOf("   "))
        assertNull(TrackMatcher.secondsOf("3"))
        assertNull(TrackMatcher.secondsOf("0:00"))
        assertNull(TrackMatcher.secondsOf("3:"))
        assertNull(TrackMatcher.secondsOf(":41"))
        assertNull(TrackMatcher.secondsOf("3:xx"))
        assertNull(TrackMatcher.secondsOf("3:41.5"))
        assertNull(TrackMatcher.secondsOf("1:2:3:4"))
        assertNull(TrackMatcher.secondsOf("-1:30"))
    }

    /**
     * Every component must be non-negative, and minutes and seconds have to
     * stay within a clock's 0..59 range; a malformed row cannot pass a length
     * check by carrying an impossible runtime.
     */
    @Test
    fun `an out-of-range time component is not a runtime`() {
        assertNull(TrackMatcher.secondsOf("3:60"))
        assertNull(TrackMatcher.secondsOf("3:-1"))
        assertNull(TrackMatcher.secondsOf("1:60:00"))
        assertNull(TrackMatcher.secondsOf("3:99:00"))
    }

    @Test
    fun `severe mismatch starts past thirty seconds in both directions`() {
        assertFalse(TrackMatcher.isSevereMismatch(200, 230))
        assertTrue(TrackMatcher.isSevereMismatch(200, 231))
        assertFalse(TrackMatcher.isSevereMismatch(200, 170))
        assertTrue(TrackMatcher.isSevereMismatch(200, 169))
        assertFalse(TrackMatcher.isSevereMismatch(null, 231))
        assertFalse(TrackMatcher.isSevereMismatch(200, null))
        assertFalse(TrackMatcher.isSevereMismatch(null, null))
    }

    @Test
    fun `videoRuntimeAllows only widens the longer side and stops at ninety seconds`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 400, isVideo = true)
        assertTrue(TrackMatcher.videoRuntimeAllows(video, song(title = "Song", artist = "Artist", duration = "6:40")))
        assertTrue(TrackMatcher.videoRuntimeAllows(video, song(title = "Song", artist = "Artist", duration = "8:10")))
        assertFalse(TrackMatcher.videoRuntimeAllows(video, song(title = "Song", artist = "Artist", duration = "8:11")))
        assertTrue(TrackMatcher.videoRuntimeAllows(video, song(title = "Song", artist = "Artist", duration = "1:00")))
        assertTrue(TrackMatcher.videoRuntimeAllows(video, song(title = "Song", artist = "Artist", duration = null)))
        assertTrue(
            TrackMatcher.videoRuntimeAllows(
                video.copy(durationSec = null),
                song(title = "Song", artist = "Artist", duration = "8:11"),
            ),
        )
    }

    // ── Album collisions ────────────────────────────────────────────────────

    @Test
    fun `conflicting releases are only asked about when the request names none`() {
        val one = song("one", "Song", "Artist", "3:20", "Album One")
        val two = song("two", "Song", "Artist", "3:20", "Album Two")
        assertTrue(TrackMatcher.hasConflictingAlbums(listOf(one, two), base))
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(one, two), base.copy(album = "Album One")))
    }

    @Test
    fun `conflicting releases without a requested runtime look at every candidate`() {
        val one = song("one", "Song", "Artist", null, "Album One")
        val two = song("two", "Song", "Artist", null, "Album Two")
        assertTrue(TrackMatcher.hasConflictingAlbums(listOf(one, two), base.copy(durationSec = null)))
    }

    @Test
    fun `only candidates within the normal window can conflict`() {
        val near = song("near", "Song", "Artist", "3:20", "Album One")
        val boundary = song("boundary", "Song", "Artist", "3:50", "Album Two")
        val outside = song("outside", "Song", "Artist", "3:51", "Album Three")
        assertTrue(TrackMatcher.hasConflictingAlbums(listOf(near, boundary), base))
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(near, outside), base))
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(outside), base))
    }

    @Test
    fun `release names are compared after edition noise is removed`() {
        val plain = song("plain", "Song", "Artist", "3:20", "Album")
        val deluxe = song("deluxe", "Song", "Artist", "3:20", "Album (Deluxe)")
        val expanded = song("expanded", "Song", "Artist", "3:20", "Album - Deluxe Edition")
        val spaced = song("spaced", "Song", "Artist", "3:20", "album  one")
        val case = song("case", "Song", "Artist", "3:20", "Album One")
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(plain, deluxe, expanded), base))
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(spaced, case), base))
    }

    @Test
    fun `an unstated release does not create a conflict`() {
        val none = song("none", "Song", "Artist", "3:20", null)
        val one = song("one", "Song", "Artist", "3:20", "Album One")
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(none, one), base))
        assertFalse(TrackMatcher.hasConflictingAlbums(listOf(none), base.copy(durationSec = null)))
    }

    @Test
    fun `a unique fuller credit resolves a close collision`() {
        val one = song("one", "Song", "Artist", "3:20", "Album One")
        val two = song("two", "Song", "Artist, Other", "3:20", "Album Two")
        assertEquals(two, TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(one, two), base))
    }

    @Test
    fun `a collision with no unique fuller credit stays unresolved`() {
        val one = song("one", "Song", "Artist", "3:20")
        val two = song("two", "Song", "Artist, Other", "3:20")
        val three = song("three", "Song", "Artist, Another", "3:20")
        assertNull(TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(one, two, three), base))
        // A single name cannot establish the canonical recording.
        assertNull(TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(one, song("x", "Song", "X", "3:20")), base))
        // Fewer than two close candidates is not a collision.
        assertNull(TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(two), base))
        // No requested runtime means nothing is close.
        assertNull(TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(one, two), base.copy(durationSec = null)))
    }

    @Test
    fun `the fuller credit must also be within the normal window`() {
        val close = song("close", "Song", "Artist, Alpha, Beta", "3:20")
        val boundary = song("boundary", "Song", "Artist, Gamma", "3:50")
        val outside = song("outside", "Song", "Artist, Delta", "3:51")
        assertEquals(close, TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(close, boundary), base))
        assertNull(TrackMatcher.uniquelyMostCreditedCloseMatch(listOf(close, outside), base))
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    fun `sameTitle ignores the artist but keeps the take apart`() {
        val plain = song("a", "Song", "Artist")
        assertTrue(TrackMatcher.sameTitle(plain, song("b", "Song (Official Video)", "Someone Else")))
        assertFalse(TrackMatcher.sameTitle(plain, song("c", "Song (Live)", "Artist")))
        assertFalse(TrackMatcher.sameTitle(plain, song("d", "Song Part 2", "Artist")))
        assertTrue(TrackMatcher.sameTitle(song("e", "コイコガレ", "A"), song("f", "koikogare", "B")))
        assertFalse(TrackMatcher.sameTitle(song("g", "", "A"), song("h", "", "B")))
    }

    @Test
    fun `sameRecording checks title take artist and cover but not runtime`() {
        assertTrue(
            TrackMatcher.sameRecording(
                song("a", "Song", "Artist", "9:00"),
                base,
            ),
        )
        assertTrue(
            TrackMatcher.sameRecording(
                song("a", "Song", "Artist, Other", "3:20"),
                base,
            ),
        )
        assertTrue(
            TrackMatcher.sameRecording(
                song("a", "koikogare", "Artist"),
                TrackMatcher.Target("コイコガレ", "Artist"),
            ),
        )
        // A different credit is refused when both sides state one.
        assertFalse(
            TrackMatcher.sameRecording(
                song("a", "Song", "Other"),
                base,
            ),
        )
        // ...but an unstated target credit cannot disagree.
        assertTrue(
            TrackMatcher.sameRecording(
                song("a", "Song", "Other"),
                base.copy(artist = ""),
            ),
        )
        assertFalse(
            TrackMatcher.sameRecording(
                song("a", "Song (Live)", "Artist"),
                base,
            ),
        )
        assertFalse(
            TrackMatcher.sameRecording(
                song("a", "Song (Karaoke Version)", "Artist"),
                base,
            ),
        )
        assertFalse(
            TrackMatcher.sameRecording(
                song("a", "", ""),
                base.copy(title = "", artist = ""),
            ),
        )
    }

    @Test
    fun `isOriginalRecording uses the exact title, not containment`() {
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song (Official Video)", "Artist", "3:20"),
                base,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song Part 2", "Artist", "3:20"),
                base,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Other", "3:20"),
                base,
            ),
        )
    }

    @Test
    fun `isOriginalRecording accepts the same title in the other script`() {
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "koikogare", "Aimer", "3:20"),
                TrackMatcher.Target("コイコガレ", "Aimer", durationSec = 200),
            ),
        )
    }

    @Test
    fun `isOriginalRecording takes channel and release identity over a display name`() {
        val byChannel = TrackMatcher.Target("Song", "Artist", durationSec = 200, artistId = "UC1")
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Other", "3:20", artistId = "UC1"),
                byChannel,
            ),
        )
        val byRelease = TrackMatcher.Target("Song", "Artist", durationSec = 200, albumId = "album-1")
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Other", "3:20", albumId = "album-1"),
                byRelease,
            ),
        )
    }

    @Test
    fun `isOriginalRecording with no requested artist cannot disagree`() {
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Anyone", "3:20"),
                base.copy(artist = ""),
            ),
        )
    }

    @Test
    fun `isOriginalRecording keeps takes apart in both directions`() {
        val live = TrackMatcher.Target("Song (Live)", "Artist", durationSec = 200)
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song (Live)", "Artist", "3:20"),
                live,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:20"),
                live,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song (Live)", "Artist", "3:20"),
                base,
            ),
        )
    }

    @Test
    fun `isOriginalRecording refuses a no-vocal release unless that take was asked for`() {
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:20", album = "Album (Instrumental Edition)"),
                base,
            ),
        )
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:20", album = "Album (Instrumental Edition)"),
                TrackMatcher.Target("Song (Instrumental)", "Artist", durationSec = 200),
            ),
        )
    }

    @Test
    fun `isOriginalRecording has its own runtime gate`() {
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:25"),
                base,
                maxDriftSec = 5,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:26"),
                base,
                maxDriftSec = 5,
            ),
        )
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:30"),
                base,
                maxDriftSec = 10,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:31"),
                base,
                maxDriftSec = 10,
            ),
        )
        assertTrue(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:20"),
                base,
                maxDriftSec = 0,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:21"),
                base,
                maxDriftSec = 0,
            ),
        )
        // A stated request needs a readable candidate runtime.
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:xx"),
                base,
            ),
        )
    }

    @Test
    fun `isOriginalRecording refuses a cover marker even at the right length`() {
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song (Originally performed by Artist)", "Artist", "3:20"),
                base,
            ),
        )
        assertFalse(
            TrackMatcher.isOriginalRecording(
                song("a", "Song", "Artist", "3:20", album = "Karaoke Hits"),
                base,
            ),
        )
    }

    // ── Covers ──────────────────────────────────────────────────────────────

    @Test
    fun `cover markers are recognised in title artist and album`() {
        listOf(
            song("a", "Song (Originally Performed by Artist)", "Anyone"),
            song("a", "Song (In the Style of Artist)", "Anyone"),
            song("a", "Song (Made Famous by Artist)", "Anyone"),
            song("a", "Song (Made Popular by Artist)", "Anyone"),
            song("a", "Song (Vocal Version)", "Anyone"),
            song("a", "Song", "Party Tyme Karaoke"),
            song("a", "Song (Karaoke)", "Anyone"),
            song("a", "Song (Tribute)", "Anyone"),
            song("a", "Song (Cover)", "Anyone"),
            song("a", "Covers of Song", "Anyone"),
            song("a", "Song (Instrumental)", "Anyone"),
            song("a", "Song (Backing Track)", "Anyone"),
            song("a", "Song (Backing Band)", "Anyone"),
            song("a", "Song (Backing Vocals)", "Anyone"),
            song("a", "Song (Piano Version)", "Anyone"),
            song("a", "Song (Guitar Cover)", "Anyone"),
            song("a", "Song (Piano Instrumental)", "Anyone"),
            song("a", "Song (Guitar Arrangement)", "Anyone"),
            song("a", "Song (Kpop Guitar)", "Anyone"),
            song("a", "Song (Idol Guitar)", "Anyone"),
            song("a", "Song (Lullaby)", "Anyone"),
            song("a", "Song (8bit)", "Anyone"),
            song("a", "Song (8-bit)", "Anyone"),
            song("a", "Song (8 bit)", "Anyone"),
            song("a", "Song (16-Bit)", "Anyone"),
            song("a", "Song (Chiptune)", "Anyone"),
            song("a", "Song (Chip Tune)", "Anyone"),
            song("a", "Song (Emulation)", "Anyone"),
            song("a", "Song (Emulated)", "Anyone"),
            song("a", "Song (Emulator)", "Anyone"),
            song("a", "Song (Video Game Version)", "Anyone"),
            song("a", "Song (Game Music)", "Anyone"),
            song("a", "Song (Game Cover)", "Anyone"),
            song("a", "Song (Arcade Player)", "Anyone"),
            song("a", "Song (커버)", "Anyone"),
            song("a", "Song (반주)", "Anyone"),
            song("a", "Song (노래방)", "Anyone"),
            song("a", "Song", "Anyone", album = "Kpop Idol Guitar"),
            song("a", "Song", "Anyone", album = "Pristine Karaoke"),
        ).forEach { candidate ->
            assertTrue(
                "must be a cover: '${candidate.title}' / '${candidate.artist}' / '${candidate.albumName}'",
                TrackMatcher.isCoverArrangement(candidate, song("seed", "Song", "Artist")),
            )
        }
    }

    @Test
    fun `a marker the seed itself carries is not a cover`() {
        assertFalse(
            TrackMatcher.isCoverArrangement(
                song("a", "Cover Me", "Artist"),
                song("seed", "Cover Me", "Artist"),
            ),
        )
        assertFalse(
            TrackMatcher.isCoverArrangement(
                song("a", "Song (Karaoke Version)", "Artist"),
                song("seed", "Song", "Artist", album = "Karaoke Hits"),
            ),
        )
        // Without a seed the marker is still a cover.
        assertTrue(TrackMatcher.isCoverArrangement(song("a", "Cover Me", "Artist")))
    }

    @Test
    fun `cover markers need whole words`() {
        listOf(
            song("a", "Recovery", "Artist"),
            song("a", "Discovery", "Artist"),
            song("a", "Undercover", "Artist"),
            song("a", "Uncovered", "Artist"),
            song("a", "8bitter", "Artist"),
        ).forEach { candidate ->
            assertFalse(
                "must not be a cover: '${candidate.title}'",
                TrackMatcher.isCoverArrangement(candidate, song("seed", "Song", "Artist")),
            )
        }
    }

    // ── Video switch ────────────────────────────────────────────────────────

    private val videoTarget = TrackMatcher.Target(
        title = "Song",
        artist = "Artist",
        durationSec = 200,
        artistId = "UC_artist",
    )

    @Test
    fun `the artist's own channel beats an official-looking title`() {
        val ownChannel = song("own", "Song", "Artist", "3:20", artistId = "UC_artist")
        val officialTitle = song("title", "Song (Official Video)", "Artist", "3:20")
        assertEquals(
            ownChannel,
            TrackMatcher.bestForVideoSwitch(listOf(officialTitle, ownChannel), videoTarget),
        )
    }

    @Test
    fun `an official channel spelling beats a title-only claim`() {
        val officialChannel = song("official", "Song", "Artist Official", "3:20")
        val titleOnly = song("title", "Song (Official Video)", "Other", "3:20")
        assertEquals(
            officialChannel,
            TrackMatcher.bestForVideoSwitch(listOf(titleOnly, officialChannel), videoTarget),
        )
    }

    @Test
    fun `fancam stage cover reaction lyric and loop rows are skipped`() {
        listOf(
            "Song (Fancam)",
            "Song 직캠",
            "Song (Live)",
            "Song 라이브",
            "Song stage",
            "Song 무대",
            "Song @ MusicBank",
            "Song 인기가요",
            "Song 엠카운트다운",
            "Song (Reaction)",
            "Song (Cover)",
            "Song (Dance Practice)",
            "Song (Lyrics)",
            "Song (Karaoke)",
            "Song 1 hour",
            "Song 교차편집",
        ).forEach { title ->
            assertNull(
                "must be skipped: '$title'",
                TrackMatcher.bestForVideoSwitch(
                    listOf(song("x", title, "Artist", "3:20", artistId = "UC_artist")),
                    videoTarget,
                ),
            )
        }
    }

    @Test
    fun `a marker is allowed when the requested title already names it`() {
        val live = TrackMatcher.Target("Song (Live)", "Artist", durationSec = 200)
        assertNotNull(
            TrackMatcher.bestForVideoSwitch(
                listOf(song("x", "Song (Live)", "Artist", "3:20", artistId = "UC_artist")),
                live,
            ),
        )
        val lyrics = TrackMatcher.Target("Song (Lyrics)", "Artist", durationSec = 200)
        assertNotNull(
            TrackMatcher.bestForVideoSwitch(
                listOf(song("x", "Song (Lyrics)", "Artist", "3:20", artistId = "UC_artist")),
                lyrics,
            ),
        )
    }

    @Test
    fun `the video switch returns null when nothing matches`() {
        assertNull(TrackMatcher.bestForVideoSwitch(emptyList(), videoTarget))
        assertNull(
            TrackMatcher.bestForVideoSwitch(
                listOf(song("x", "Other", "Artist", "3:20", artistId = "UC_artist")),
                videoTarget,
            ),
        )
    }

    // ── Official audio for a video ──────────────────────────────────────────

    @Test
    fun `a video switches to the matching catalogue song`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 200, isVideo = true, artistId = "UC1")
        val audio = song("audio", "Song", "Artist", "3:20", artistId = "UC1")
        assertEquals(audio, TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))
    }

    @Test
    fun `the official-audio switch requires an exact title`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 200, isVideo = true)
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("x", "Song Part 2", "Artist", "3:20")),
                video,
            ),
        )
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("x", "Song (Live)", "Artist", "3:20")),
                video,
            ),
        )
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("x", "", "", "3:20")),
                video.copy(title = ""),
            ),
        )
    }

    @Test
    fun `the official-audio switch bridges scripts and release identity`() {
        val video = TrackMatcher.Target("コイコガレ", "Aimer", durationSec = 200, isVideo = true)
        val audio = song("x", "koikogare", "Aimer", "3:20")
        assertEquals(audio, TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))

        val byRelease = TrackMatcher.Target(
            "Song",
            "Artist",
            durationSec = 200,
            isVideo = true,
            albumId = "album-1",
        )
        val alias = song("alias", "Song", "Other", "3:20", albumId = "album-1")
        assertEquals(alias, TrackMatcher.bestOfficialAudioForVideo(listOf(alias), byRelease))
    }

    @Test
    fun `the official-audio switch refuses a cover and a no-vocal album`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 200, isVideo = true)
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("x", "Song (Karaoke Version)", "Artist", "3:20")),
                video,
            ),
        )
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("x", "Song", "Artist", "3:20", album = "Album (Instrumental Edition)")),
                video,
            ),
        )
    }

    @Test
    fun `the official-audio switch allows the instrumental take it asked for`() {
        val video = TrackMatcher.Target(
            "Song (Instrumental)",
            "Artist",
            durationSec = 200,
            isVideo = true,
        )
        val audio = song(
            "x",
            "Song (Instrumental)",
            "Artist",
            "3:20",
            album = "Album (Instrumental Edition)",
        )
        assertEquals(audio, TrackMatcher.bestOfficialAudioForVideo(listOf(audio), video))
    }

    @Test
    fun `the official-audio runtime window is one-sided and stops at ninety seconds`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 400, isVideo = true)
        assertNotNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("short", "Song", "Artist", "1:00")),
                video,
            ),
        )
        assertNotNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("boundary", "Song", "Artist", "8:10")),
                video,
            ),
        )
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("outside", "Song", "Artist", "8:11")),
                video,
            ),
        )
        // An unstated runtime cannot be checked, but it is not a veto here.
        assertNotNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("unknown", "Song", "Artist", null)),
                video,
            ),
        )
    }

    @Test
    fun `the official-audio switch prefers an exact credit over a shared one`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 200, isVideo = true)
        val shared = song("shared", "Song", "Artist, Other", "3:20")
        val exact = song("exact", "Song", "Artist", "3:22")
        assertEquals(
            exact,
            TrackMatcher.bestOfficialAudioForVideo(listOf(shared, exact), video),
        )
    }

    @Test
    fun `the official-audio switch refuses another artist's song`() {
        val video = TrackMatcher.Target("Song", "Artist", durationSec = 200, isVideo = true)
        assertNull(
            TrackMatcher.bestOfficialAudioForVideo(
                listOf(song("x", "Song", "Other", "3:20")),
                video,
            ),
        )
    }

    // ── Matches ─────────────────────────────────────────────────────────────

    @Test
    fun `matches is a yes or no over the same score`() {
        assertTrue(
            TrackMatcher.matches(
                song(title = "Song", artist = "Artist", duration = "3:20"),
                title = "Song",
                artist = "Artist",
                durationSec = 200,
            ),
        )
        assertFalse(
            TrackMatcher.matches(
                song(title = "Song", artist = "Artist", duration = "4:51"),
                title = "Song",
                artist = "Artist",
                durationSec = 200,
            ),
        )
        assertFalse(
            TrackMatcher.matches(
                song(title = "Other", artist = "Artist", duration = "3:20"),
                title = "Song",
                artist = "Artist",
                durationSec = 200,
            ),
        )
    }
}
