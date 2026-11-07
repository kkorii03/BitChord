package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cases taken from a real cover singer's playlist, where exact matching found no original. */
class RecordingPickTest {

    private fun row(id: String, title: String, artist: String, length: String = "3:30", video: Boolean = false) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length, isVideo = video)

    private fun pick(title: String, artist: String, rows: List<Song>, videoMs: Long = 215_000L) =
        RecordingPick.rank(listOf(title), listOf(artist), rows, videoMs).map { it.videoId }

    @Test
    fun aTranslationInBracketsStillFindsTheJapaneseTitle() {
        val rows = listOf(
            row("a", "ダンスホール - Dance Hall", "Mrs. GREEN APPLE"),
            row("b", "ダンスホール (Instrumental) - Dance Hall (Instrumental)", "Mrs. GREEN APPLE"),
            row("c", "ダンスホール (~The White Lounge Version~ / Live) - Dance Hall", "Mrs. GREEN APPLE", "4:10"),
            row("d", "夢見るダンスホール - Dreaming dance hall", "CHOP STICK, Chee"),
        )
        assertEquals("a", pick("ダンスホール(Dance Hall, 댄스홀)", "Mrs. Green Apple", rows).first())
    }

    @Test
    fun theArtistsOwnRecordBeatsANamesakeWithTheExactTitle() {
        // From the listener's playlist: "i hate you, i love you(gnash) / 오화요Cover".
        val rows = listOf(
            row("x", "I Hate You, I Love You", "Teesha, Young One", "3:50"),
            row("y", "I Hate You I Love You", "Mark Miralles & Patrick Hizon", "3:44"),
            row("g", "i hate u, i love u (feat. Olivia O'brien)", "gnash", "4:11"),
        )
        assertEquals("g", pick("i hate you, i love you", "gnash", rows, videoMs = 229_000L).first())
    }

    @Test
    fun theWrongArtistDoesNotHideTheRightTitle() {
        val rows = listOf(
            row("a", "がらくた - JUNK - Garakuta - JUNK", "Kenshi Yonezu"),
            row("b", "곱게 자랐어요 (feat. 하은)", "커피소년"),
            row("c", "あくびがうつる - Yawning is contagious", "Garakuta"),
        )
        assertEquals("a", pick("がらくた(잡동사니)", "오화요", rows).first())
    }

    @Test
    fun aKoreanTitleMeetsItsEnglishListingInBrackets() {
        val rows = listOf(
            row("a", "The Western Sky (서쪽 하늘)", "Lee Seung Chul", "4:50"),
            row("b", "서쪽 하늘", "울랄라세션", "4:40"),
            row("c", "The Western Sky (inst.) (서쪽하늘 (inst.))", "Lee Seung Chul", "4:50"),
        )
        val picked = pick("서쪽하늘", "이승철", rows, videoMs = 290_000L)
        assertTrue(picked.first() in setOf("a", "b") && "c" !in picked, "$picked")
    }

    @Test
    fun anEnglishOnlyListingIsTriedWhenItLeadsTheSearchAndFits() {
        val rows = listOf(
            row("a", "This Love", "Davichi", "4:05"),
            row("b", "외사랑", "Davichi", "3:40"),
            row("c", "Everytime", "CHEN, Punch", "3:10"),
        )
        assertEquals("a", pick("이 사랑", "다비치", rows, videoMs = 248_000L).first())
    }

    @Test
    fun versionsTheCoverDidNotAskForGoLast() {
        val rows = listOf(
            row("b", "踊 (Cover Ver.) [Mixed] - Odo (Cover Ver.) [Mixed]", "MIKA HIRAMA"),
            row("c", "踊 (Bon-Odo Remix) - Odo (Bon-Odo Remix)", "Ado"),
            row("a", "踊 - Odo", "Ado"),
        )
        assertEquals("a", pick("踊 (Odo, 춤)", "Ado", rows).first())
        val lists = listOf(
            row("x", "Let It Go (From \"Frozen\"/Soundtrack Version)", "Idina Menzel", "3:44"),
            row("y", "Let It Go (Karaoke Version) (Originally Performed By Idina Menzel)", "Karaoke Galaxy", "3:44"),
        )
        assertEquals(listOf("x"), pick("Let It Go", "Idina Menzel", lists, videoMs = 225_000L).take(1))
    }

    @Test
    fun karaokeAndNamesakeRowsAreNotTheRecord() {
        // The Hills' failed alignment (2026-10-23) measured against a Party
        // Tyme karaoke arrangement and The Weeknd's unrelated "Lost in the
        // Fire". Neither is the record; only the plain catalogue row is.
        val rows = listOf(
            row("k", "The Hills (Made Popular By The Weeknd) [Vocal Version]", "Party Tyme Karaoke", "3:55"),
            row("w", "The Hills", "The Weeknd", "3:55"),
            row("o", "Lost in the Fire", "The Weeknd", "3:42"),
        )
        assertEquals(listOf("w"), pick("The Hills", "The Weeknd", rows, videoMs = 243_000L))
    }

    @Test
    fun withoutTheRecordThereIsNothingToMeasureAgainst() {
        val rows = listOf(
            row("k", "The Hills (Made Popular By The Weeknd) [Vocal Version]", "Party Tyme Karaoke", "3:55"),
            row("o", "Lost in the Fire", "The Weeknd", "3:42"),
        )
        assertEquals(emptyList(), pick("The Hills", "The Weeknd", rows, videoMs = 243_000L))
    }

    @Test
    fun anUploadOfAnotherSongOrActIsNotTheRecord() {
        val rows = listOf(
            row("v", "Flashlight", "Somebody Else", video = true),
            row("s", "Completely Different", "Jessie J", "9:59"),
        )
        assertEquals(emptyList(), pick("Flashlight", "Jessie J", rows))
    }

    /**
     * 11-07 rs5/rs6: all 216 `no original recording found` lines follow a
     * youtube.com fallback, whose rows are uploads; the first pass dropped
     * every one of them, so 41 of 45 base=none video rows had no reference at
     * all. When the catalogue named nothing, the act's own upload of the song
     * is the recording to measure against.
     */
    @Test
    fun theActsOwnUploadStandsInWhenTheCatalogueNamedNothing() {
        val rows = listOf(
            row("v", "Lose Yourself (Official Music Video)", "EminemVEVO", "5:26", video = true),
            row("s", "Lose Yourself", "Some Other Band", "5:26", video = true),
            row("k", "Lose Yourself (Made Popular By Eminem)", "Party Tyme Karaoke", "5:26", video = true),
        )
        assertEquals(listOf("v"), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    @Test
    fun aLiveOrCoverUploadIsNotTheRecord() {
        val rows = listOf(
            row("l", "Lose Yourself (Live at Wembley)", "Eminem", "5:40", video = true),
            row("c", "Lose Yourself (Cover)", "Eminem", "5:26", video = true),
        )
        assertEquals(emptyList(), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /** A different cut is a different recording, however exactly it names the song. */
    @Test
    fun anUploadOfAnotherCutIsNotTheRecord() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", "9:50", video = true))
        assertEquals(emptyList(), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /** The row playing is already its own audio; measuring it against itself says nothing. */
    @Test
    fun theRowPlayingIsNeverItsOwnReference() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", "5:26", video = true))
        assertEquals(
            emptyList(),
            RecordingPick.rank(listOf("Lose Yourself"), listOf("Eminem"), rows, 326_000L, 2, "v")
                .map { it.videoId },
        )
    }

    /**
     * 11-07 review M1: the lyrics name list carries `title to ""` for a cover
     * that never names the original. With no act, the strict gate has no
     * artist to check, so any uploader's exact-titled upload stood in.
     */
    @Test
    fun anUploadIsNotTheRecordWhenTheActIsUnknown() {
        val rows = listOf(
            row("x", "Lose Yourself", "Some Other Band", "5:26", video = true),
            row("y", "Lose Yourself (Official Music Video)", "EminemVEVO", "5:26", video = true),
        )
        assertEquals(emptyList(), pick("Lose Yourself", "", rows, videoMs = 326_000L))
    }

    /** The blank entry must not hide the name that does state the act. */
    @Test
    fun aBlankNameDoesNotHideTheActTheOtherNameStates() {
        val rows = listOf(
            row("x", "Lose Yourself", "Some Other Band", "5:26", video = true),
            row("y", "Lose Yourself (Official Music Video)", "EminemVEVO", "5:26", video = true),
        )
        assertEquals(
            listOf("y"),
            RecordingPick.rank(
                listOf("Lose Yourself", "Lose Yourself"),
                listOf("", "Eminem"),
                rows,
                326_000L,
            ).map { it.videoId },
        )
    }

    /**
     * The review's M2 case, closed: the take is named only in the snippet the
     * search row prints, and the title reads exactly `Song`. Without the
     * description the gate saw a plain-titled upload by the act and admitted
     * a live take as the record.
     */
    @Test
    fun anUploadWhoseDescriptionNamesAnotherTakeIsNotTheRecord() {
        val rows = listOf(
            row("v", "Lose Yourself", "Eminem", "5:26", video = true)
                .copy(description = "Lose Yourself (Live at Wembley) · 2002"),
        )
        assertEquals(emptyList(), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /**
     * The description guard must not refuse what the request did ask for: an
     * official video's snippet is full of packaging words ("Official", "Video",
     * "From …"), and those are context, never a take.
     */
    @Test
    fun anUploadWithOnlyPackagingInItsDescriptionIsTheRecord() {
        val rows = listOf(
            row("v", "Lose Yourself", "Eminem", "5:26", video = true)
                .copy(description = "Official music video for Lose Yourself (From \"8 Mile\") [Official Video]"),
        )
        assertEquals(listOf("v"), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /**
     * The take the request did ask for still passes: the snippet and the target
     * agree on "live", so the row is the live take the caller asked about.
     */
    @Test
    fun anUploadWithTheTakeTheRequestAskedForIsTheRecord() {
        val rows = listOf(
            row("v", "Lose Yourself (Live at Wembley)", "Eminem", "5:26", video = true)
                .copy(description = "Lose Yourself (Live at Wembley) · 2002"),
        )
        assertEquals(
            listOf("v"),
            pick("Lose Yourself (Live at Wembley)", "Eminem", rows, videoMs = 326_000L),
        )
    }

    /**
     * 11-07 review M2: an upload that names its take only on its release
     * ("Live at Wembley") is still not the record. A youtube.com search row
     * carries no album, so this guard only ever sees a row that states one.
     */
    @Test
    fun anUploadWhoseReleaseNamesAnotherTakeIsNotTheRecord() {
        val rows = listOf(
            row("v", "Lose Yourself", "Eminem", "5:26", video = true)
                .copy(albumName = "Lose Yourself (Live at Wembley)"),
        )
        assertEquals(emptyList(), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /**
     * 11-07 review M3: the runtime rule is the video's, not a symmetric drift.
     * A 6:30 music video can carry an intro its 4:00 single does not; the
     * single is still the record. Old code refused it outright (150 s apart).
     */
    @Test
    fun theMasterMayBeShorterThanItsVideo() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", "4:00", video = true))
        assertEquals(listOf("v"), pick("Lose Yourself", "Eminem", rows, videoMs = 390_000L))
    }

    /** The other direction stays refused: a 7:10 cut under a 5:26 video is another edit. */
    @Test
    fun anUploadMuchLongerThanItsVideoIsNotTheRecord() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", "7:10", video = true))
        assertEquals(emptyList(), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /**
     * The reused video rule's longer side is `VIDEO_DURATION_LIMIT_SEC` itself:
     * 6:50 against a 5:26 video (84 s) is still offered as the record. Pinned
     * at the rule's own number on purpose — the picker must read
     * [com.music.bitchord.data.sources.TrackMatcher.videoRuntimeAllows] exactly
     * as the audio switch does, not with a tighter number of its own.
     */
    @Test
    fun anUploadWithinTheVideoAllowanceLongerThanItsVideoIsStillTheRecord() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", "6:50", video = true))
        assertEquals(listOf("v"), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /** An upload that states no runtime cannot be checked at all. */
    @Test
    fun anUploadWithoutARuntimeIsNotTheRecord() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", video = true).copy(durationText = null))
        assertEquals(emptyList(), pick("Lose Yourself", "Eminem", rows, videoMs = 326_000L))
    }

    /**
     * 11-07 review LOW 5: a catalogue row with the playing video's own id is
     * what used to be picked, and the caller reads that id as "the video is
     * the record". Excluding it must not turn the row into "no original
     * found", which makes a paid automatic request eligible.
     */
    @Test
    fun theCatalogueRowOfThePlayingVideoStillRecordsNoChange() {
        val rows = listOf(row("v", "Lose Yourself", "Eminem", "5:26"))
        assertEquals(
            listOf("v"),
            RecordingPick.rank(listOf("Lose Yourself"), listOf("Eminem"), rows, 326_000L, 2, "v")
                .map { it.videoId },
        )
    }

    /**
     * The old candidate list is returned whole — the playing row included when
     * the catalogue pass named it — because that list is exactly what the
     * pre-exclude code handed over, and the caller reads the id as "the video
     * is the record" and returns before any download. The extra row is not a
     * reference; nothing measures against it.
     */
    @Test
    fun thePlayingRowsOwnRowIsInTheCandidatesItUsedToBeIn() {
        val rows = listOf(
            row("v", "Lose Yourself", "Eminem", "5:26"),
            row("o", "Lose Yourself", "Eminem", "5:25"),
        )
        assertEquals(
            listOf("v", "o"),
            RecordingPick.rank(listOf("Lose Yourself"), listOf("Eminem"), rows, 326_000L, 2, "v")
                .map { it.videoId },
        )
    }

    /**
     * The verdict is the *old candidate list's*, not "the row would score":
     * with three equal rows and `max = 2` the catalogue pass kept only the
     * first two, so the playing row was never the record it now claims to be.
     * Restoring more than the old code did would refuse a reference it used.
     */
    @Test
    fun thePlayingRowOnlyWinsWhenTheCataloguePassWouldHaveNamedIt() {
        val rows = listOf(
            row("a", "Lose Yourself", "Eminem", "5:26"),
            row("b", "Lose Yourself", "Eminem", "5:26"),
            row("v", "Lose Yourself", "Eminem", "5:26"),
        )
        assertEquals(
            listOf("a", "b"),
            RecordingPick.rank(listOf("Lose Yourself"), listOf("Eminem"), rows, 326_000L, 2, "v")
                .map { it.videoId },
        )
    }

    /**
     * 2026-11-05 smoke: from a Korean IP without Premium Music's Songs filter
     * answered no rows at all, the page was taken at its word, and all eight
     * covers in the run read "no original recording found" — so the cover
     * sheet's times stayed and the whole song drifted (+4.31 s on 7PgA6kyqVTc).
     * youtube.com answers the same tracks, which is what this falls back to.
     */
    @Test
    fun anEmptySongsPageFallsBackToTheYouTubeRows() {
        val record = row("orig", "Youngblood", "5 Seconds of Summer - Topic", "3:23")
        assertEquals(listOf(record), RecordingPick.pageRows(emptyList<SearchResult>()) { listOf(record) })
    }

    @Test
    fun aPageWithRowsIsNotSearchedAgain() {
        val mine = row("a", "Youngblood", "5 Seconds of Summer")
        val rows = RecordingPick.pageRows(listOf(SearchResult.Track(mine))) { error("a second search") }
        assertEquals(listOf(mine), rows)
    }

    /** The fallback's rows are youtube.com's: a "- Topic" credit and a "m:ss" badge. */
    @Test
    fun theYouTubeRowShapeOfTheRecordIsPicked() {
        val rows = listOf(
            row("v", "Youngblood (Official Video)", "5 Seconds of Summer", "3:44", video = true),
            row("o", "Youngblood", "5 Seconds of Summer - Topic", "3:23"),
        )
        assertEquals(listOf("o"), pick("Youngblood", "5 Seconds of Summer", rows, videoMs = 203_000L))
    }
}
