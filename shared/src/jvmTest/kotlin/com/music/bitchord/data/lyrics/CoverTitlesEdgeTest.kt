package com.music.bitchord.data.lyrics

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cover-title splitting, reading and the fields a row keeps once a cover is applied. */
class CoverTitlesEdgeTest {

    private val singers = CoverTitles.knownSingers
    private val channels = CoverTitles.designatedChannels
    private val original = CoverTitles.knownOriginal
    private val seen = CoverTitles.onSingerSeen

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = singers
        CoverTitles.designatedChannels = channels
        CoverTitles.knownOriginal = original
        CoverTitles.onSingerSeen = seen
    }

    private fun song(
        title: String,
        channel: String,
        videoId: String = "test",
        isVideo: Boolean = true,
        originalTitle: String? = null,
        originalArtist: String? = null,
        coverSinger: String? = null,
        coverOriginalArtist: String? = null,
    ) = Song(
        videoId = videoId,
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = isVideo,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        coverSinger = coverSinger,
        coverOriginalArtist = coverOriginalArtist,
    )

    private fun cover(title: String, channel: String) =
        assertNotNull(CoverTitles.read(title, channel, null), title)

    // ---- Splitting -------------------------------------------------------------------

    @Test
    fun aDashSplitsOnlyBetweenWordsUnlessLoose() {
        assertEquals("A" to "B", CoverTitles.splitTopLevelDash("A - B"))
        assertNull(CoverTitles.splitTopLevelDash("A-B"))
        assertEquals("A" to "B", CoverTitles.splitTopLevelDash("A-B", unspaced = true))
        assertNull(CoverTitles.splitTopLevelDash("Re-Love"))
        assertEquals("Re" to "Love", CoverTitles.splitTopLevelDash("Re-Love", unspaced = true))
        assertEquals("A" to "B", CoverTitles.splitTopLevelDash("A – B"))
        assertEquals("A" to "B", CoverTitles.splitTopLevelDash("A—B"))
        assertNull(CoverTitles.splitTopLevelDash("A (B-C) D"))
        assertEquals("A" to "B - C", CoverTitles.splitTopLevelDash("A - B - C"))
        assertNull(CoverTitles.splitTopLevelDash("A -"))
        assertNull(CoverTitles.splitTopLevelDash("-ERROR"))
        assertNull(CoverTitles.splitTopLevelDash(""))
    }

    @Test
    fun anUnderscoreSplitsOnlyWhenSpaced() {
        assertNull(CoverTitles.splitTopLevelDash("snake_case"))
        assertEquals("A" to "B", CoverTitles.splitTopLevelDash("A _ B"))
    }

    @Test
    fun aSlashSplitsOnlyWhenSpacedOrAfterABracketOrFullWidth() {
        assertEquals("A" to "B", CoverTitles.splitTopLevelSlash("A / B"))
        assertNull(CoverTitles.splitTopLevelSlash("A/B"))
        assertNull(CoverTitles.splitTopLevelSlash("AC/DC"))
        assertEquals("A" to "B", CoverTitles.splitTopLevelSlash("A／B"))
        assertEquals("Song(artist)" to "singer", CoverTitles.splitTopLevelSlash("Song(artist)/singer"))
        assertEquals("A / B" to "C", CoverTitles.splitTopLevelSlash("A / B / C"))
        assertEquals("A (B/C)" to "D", CoverTitles.splitTopLevelSlash("A (B/C) / D"))
        assertNull(CoverTitles.splitTopLevelSlash("A / "))
        assertNull(CoverTitles.splitTopLevelSlash(""))
    }

    // ---- Reading ---------------------------------------------------------------------

    @Test
    fun blankAndTagOnlyTitlesAreNotCovers() {
        assertNull(CoverTitles.read("", "channel", null))
        assertNull(CoverTitles.read("   ", "channel", null))
        assertNull(CoverTitles.read("[4K]", "channel", null))
        assertNull(CoverTitles.read("[커버]", "channel", null))
    }

    @Test
    fun aBareLatinCoverWordNeedsAnotherWordButABracketDoesNot() {
        assertNull(CoverTitles.read("Take Cover", "channel", null))
        assertNull(CoverTitles.read("Song cover", "channel", null))
        val long = cover("Long Song cover", "channel")
        assertEquals("Long Song", long.title)
        assertEquals("channel", long.singer)
        assertNull(long.originalArtist)

        val bracketed = cover("Song (Cover)", "channel")
        assertEquals("Song", bracketed.title)
        assertEquals("channel", bracketed.singer)
        val square = cover("Song [Cover]", "channel")
        assertEquals("Song", square.title)
        assertEquals("channel", square.singer)
    }

    @Test
    fun everyCoverBySpellingNamesTheSinger() {
        listOf("Song (cover by X)", "Song (Covered by X)", "Song (covey by X)", "Song / cover by X").forEach {
            val read = cover(it, "channel")
            assertEquals("Song", read.title, it)
            assertEquals("X", read.singer, it)
            assertNull(read.originalArtist, it)
        }
    }

    @Test
    fun aSlashNameAfterTheSongIsTheSingerOnAnyChannel() {
        val read = cover("Song / X cover", "channel")
        assertEquals("Song", read.title)
        assertEquals("X", read.singer)
        assertNull(read.originalArtist)
    }

    @Test
    fun aCoverWordAfterANameNeedsThatNameToBeTheChannel() {
        val own = cover("Song - 타비 Cover", "타비")
        assertEquals("Song", own.title)
        assertEquals("타비", own.singer)
        assertEquals(null, own.originalArtist)

        val other = CoverTitles.read("Song - 타비 Cover", "다른 채널", null)
        assertTrue(other == null || other.singer != "타비")
        val singerFirst = cover("타비 - Song Cover", "타비")
        assertEquals("Song", singerFirst.title)
        assertEquals("타비", singerFirst.singer)
    }

    @Test
    fun aClosingSingerBracketKeepsTheOrderUnsure() {
        val read = cover("Song - Artist (Singer Cover)", "channel")
        assertEquals("Song", read.title)
        assertEquals("Artist", read.originalArtist)
        assertEquals("Singer", read.singer)
        assertTrue(read.orderUnsure)
    }

    @Test
    fun aDatedBTitleReadsTheBracketAsTheOriginal() {
        val read = cover("Singer - Song(Original) [24.10.10]", "channel")
        assertEquals("Song", read.title)
        assertEquals("Original", read.originalArtist)
        assertEquals("Singer", read.singer)
    }

    @Test
    fun aCoverTitleWithAnOriginalCreditAndNoDashReadsTheOriginal() {
        val read = cover("정승환 '0+0' Cover (원곡 : 한로로)", "정승환")
        assertEquals("0+0", read.title)
        assertEquals("한로로", read.originalArtist)
        assertEquals("정승환", read.singer)
    }

    @Test
    fun anOriginalArtistWhoseNameContainsAMarkerWordIsStillAName() {
        listOf("Mr. Big", "The Piano Guys", "AI").forEach { artist ->
            val read = cover("Singer - Song (원곡 : $artist)", "channel")
            assertEquals(artist, read.originalArtist, artist)
            val noDash = cover("Singer 'Song' Cover (원곡 : $artist)", "Singer")
            assertEquals(artist, noDash.originalArtist, artist)
        }
    }

    @Test
    fun aBareVersionOrDescriptorOriginalCreditIsNotAnArtist() {
        listOf("Live", "Acoustic").forEach { marker ->
            assertNull(CoverTitles.read("Singer - Song (원곡 : $marker)", "channel", null), marker)
            assertNull(CoverTitles.read("Singer 'Song' Cover (원곡 : $marker)", "Singer", null), marker)
        }
    }

    @Test
    fun englishOriginalCreditsNameTheOriginalArtist() {
        listOf(
            "Singer - Song (Original by Writer)",
            "Singer - Song (orig. Writer)",
            "Singer - Song Cover orig. Writer",
        ).forEach { title ->
            val read = cover(title, "channel")
            assertEquals("Writer", read.originalArtist, title)
        }
    }

    @Test
    fun originalVersionBracketsAreNotOriginalArtistCredits() {
        listOf(
            "IU - Blueming (Original Mix)",
            "IU - Blueming (Original Version)",
            "IU - Blueming (Original Soundtrack)",
        ).forEach { title ->
            assertNull(CoverTitles.read(title, "IU", null), title)
            assertFalse(CoverTitles.isCover(song(title, "IU")), title)
        }
    }

    @Test
    fun aTranslationBracketOnADatedBTitleIsNotAnArtist() {
        val read = cover("가수 - 봄날(Spring Day) [24.10.10]", "channel")
        assertEquals("봄날", read.title)
        assertNull(read.originalArtist)
        assertEquals("가수", read.singer)
    }

    @Test
    fun anUndatedTranslationBracketAloneIsNotACover() {
        assertNull(CoverTitles.read("가수 - 봄날(Spring Day)", "channel", null))
    }

    @Test
    fun aKnownOriginalSpellingReplacesAHandTypedCoverTitle() {
        CoverTitles.knownOriginal = { id -> if (id == "v") "Shape of You" to "Ed Sheeran" else null }
        val read = assertNotNull(CoverTitles.read(song("Shape of you(Edsheren) / 오화요Cover", "channel", videoId = "v")))
        assertEquals("Shape of You", read.title)
        assertEquals("Ed Sheeran", read.originalArtist)
        assertFalse(read.orderUnsure)
    }

    @Test
    fun aBlankKnownOriginalKeepsWhatTheTitleSaid() {
        CoverTitles.knownOriginal = { "" to "" }
        val read = assertNotNull(CoverTitles.read(song("Song - Artist (Singer Cover)", "channel", videoId = "v")))
        assertEquals("Song", read.title)
        assertEquals("Artist", read.originalArtist)
        assertEquals("Singer", read.singer)
        assertFalse(read.orderUnsure)
    }

    @Test
    fun aFailingKnownOriginalLookupIsIgnored() {
        CoverTitles.knownOriginal = { error("boom") }
        val read = assertNotNull(CoverTitles.read(song("Song / X cover", "channel", videoId = "v")))
        assertEquals("Song", read.title)
        assertEquals("X", read.singer)
    }

    @Test
    fun aCertainCoverTellsTheSingerIndexButABareTagDoesNot() {
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }
        cover("Song / X cover", "channel")
        assertEquals(listOf("X"), told)
        told.clear()
        cover("Song (Cover)", "channel")
        assertTrue(told.isEmpty())
    }

    // ---- The read row -----------------------------------------------------------------

    @Test
    fun aBylineNamesTheOriginalAndTheSinger() {
        assertEquals("Singer 커버", CoverTitles.Cover("Song", null, "Singer").byline)
        assertEquals("Original · Singer 커버", CoverTitles.Cover("Song", "Original", "Singer").byline)
    }

    @Test
    fun creditedArtistPrefersTheSingerFieldThenTheByline() {
        assertEquals("Singer", CoverTitles.creditedArtist(song("Song", "Original · Singer 커버", coverSinger = "Singer")))
        assertEquals("Singer", CoverTitles.creditedArtist(song("Song", "Original · Singer 커버")))
        assertEquals("Singer", CoverTitles.creditedArtist(song("Song", "Singer 커버")))
        assertEquals("Artist", CoverTitles.creditedArtist(song("Song", "Artist")))
        assertEquals("Singer", CoverTitles.creditedArtist(song("Song", "Original · Singer 커버", coverSinger = "")))
    }

    @Test
    fun creditedOriginalArtistComesFromTheFieldOrTheByline() {
        assertEquals("Original", CoverTitles.creditedOriginalArtist(song("Song", "x", coverOriginalArtist = "Original")))
        assertEquals("Original", CoverTitles.creditedOriginalArtist(song("Song", "Original · Singer 커버")))
        assertEquals("Original", CoverTitles.creditedOriginalArtist(song("Song", "원곡: Original · Singer 커버")))
        assertNull(CoverTitles.creditedOriginalArtist(song("Song", "Singer 커버")))
        assertNull(CoverTitles.creditedOriginalArtist(song("Song", "Artist")))
        assertEquals(
            "Original",
            CoverTitles.creditedOriginalArtist(song("Song", "Original · Singer 커버", coverOriginalArtist = "")),
        )
    }

    @Test
    fun applyingACoverKeepsTheUploadAndShowsTheSong() {
        val upload = song("A - Song / cover by Singer", "channel")
        val applied = CoverTitles.applyTo(upload)
        assertEquals("Song", applied.title)
        assertEquals("A · Singer 커버", applied.artist)
        assertEquals("A - Song / cover by Singer", applied.originalTitle)
        assertEquals("channel", applied.originalArtist)
        assertEquals("Singer", applied.coverSinger)
        assertEquals("A", applied.coverOriginalArtist)
        assertSame(applied, CoverTitles.applyTo(applied))
    }

    @Test
    fun applyingANonCoverChangesNothing() {
        val ordinary = song("IU - 밤편지", "IU")
        assertSame(ordinary, CoverTitles.applyTo(ordinary))
    }

    @Test
    fun isCoverRecognisesEveryStoredShape() {
        assertTrue(CoverTitles.isCover(song("Song / X cover", "channel")))
        assertFalse(CoverTitles.isCover(song("IU - 밤편지", "IU")))
        assertTrue(CoverTitles.isCover(song("Song", "Artist", originalTitle = "upload")))
        assertTrue(CoverTitles.isCover(song("Song", "Artist", coverSinger = "Singer")))
        assertTrue(CoverTitles.isCover(song("Song", "Singer 커버")))
        assertTrue(CoverTitles.isCover(song("Song", "Singer 커버", isVideo = false)))
    }

    @Test
    fun aLostCoverBylineIsReconstructedForTheLyricsSearch() {
        val query = CoverTitles.lyricsQuery(song("Song", "Original · Singer 커버", isVideo = true))
        assertEquals("Song", query.title)
        assertEquals("Original", query.artist)
        assertEquals(listOf("Song" to "Singer"), query.alternatives)
        assertTrue(query.isVideo)
        assertTrue(query.artistKnown)

        val singerOnly = CoverTitles.lyricsQuery(song("Song", "Singer 커버", isVideo = true))
        assertEquals("Song", singerOnly.title)
        assertEquals("Singer", singerOnly.artist)
        assertTrue(singerOnly.alternatives.isEmpty())
        assertFalse(singerOnly.artistKnown)
    }

    @Test
    fun anAppliedCoverSearchesAsTheOriginalUpload() {
        val applied = CoverTitles.applyTo(song("A - Song / cover by Singer", "channel"))
        val query = CoverTitles.lyricsQuery(applied)
        assertEquals("Song", query.title)
        assertEquals("A", query.artist)
        assertTrue(query.alternatives.contains("Song" to "Singer"))
        assertTrue(query.artistKnown)
    }

    @Test
    fun anOrdinaryVideoTitleSearchesAsItself() {
        val query = CoverTitles.lyricsQuery(song("IU - 밤편지", "IU"))
        assertEquals("IU - 밤편지", query.title)
        assertEquals("IU", query.artist)
        assertTrue(query.alternatives.isEmpty())
    }

    @Test
    fun aTrailingVoicebankBracketIsSearchedBothWays() {
        val queries = CoverTitles.lyricsQueries(song("Song - Artist (VOCALOID)", "channel"))
        assertTrue(queries.contains("Artist" to "Song"))
        assertTrue(queries.contains("Artist" to "VOCALOID"))
    }

    /** Phone 11-04: "Memories / 오화요" kept the singer in the title. */
    @Test
    fun aKnownSingerAfterTheSongSlashIsTheSinger() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val read = cover("Memories / 오화요", "허니즈 HONEYZ")
        assertEquals("Memories", read.title)
        assertEquals("오화요", read.singer)
        assertNull(read.originalArtist)
        // A medley is two songs, not a song and a singer.
        assertNull(CoverTitles.read("Dynamite / Butter", "허니즈 HONEYZ", null))
    }

    /** A singer no cover has named yet leaves the title on the first sight of it. */
    @Test
    fun aNewSingerAfterTheSongSlashIsReadAndLearned() {
        CoverTitles.knownSingers = { emptySet() }
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }

        val read = cover("Something / 오화요", "허니즈 HONEYZ")
        assertEquals("Something", read.title)
        assertEquals("오화요", read.singer)
        assertNull(read.originalArtist)
        assertEquals(listOf("오화요"), told)

        // Learned: the same name in a shape the first sight would refuse reads now.
        CoverTitles.knownSingers = { setOf("오화요") }
        val short = cover("사랑 / 오화요", "허니즈 HONEYZ")
        assertEquals("사랑", short.title)
        assertEquals("오화요", short.singer)
    }

    /** The slash shapes that are a title, a translation or an artist, not a singer. */
    @Test
    fun aSlashThatIsNotASingerLeavesTheTitleWhole() {
        CoverTitles.knownSingers = { emptySet() }
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }
        listOf(
            // A medley: both halves are the song, in one script.
            "Dynamite / Butter",
            // A translated title names the song twice.
            "봄날 / Spring Day",
            "Spring Day / 봄날",
            // "Artist / Song" on the artist's own upload is the artist first.
            "IU / Blueming",
            // A take, a tag or a clip word after the slash.
            "Dynamite / Live",
            "Dynamite / 노래방",
            // The tail is already part of the song's name.
            "Hype Boy / Boy",
        ).forEach { title ->
            assertNull(CoverTitles.read(title, "IU", null), title)
        }
        assertTrue(told.isEmpty(), told.toString())
    }

    /** Phone 11-04: a cleaned byline was cleaned again, one word at a time. */
    @Test
    fun aBylineKeepsOneCoverWord() {
        assertEquals("오화요 커버", CoverTitles.Cover("Song", null, "오화요 커버 커버 커버").byline)
        assertEquals("원곡자 · 오화요 커버", CoverTitles.Cover("Song", "원곡자 커버", "오화요 커버").byline)
    }

    // ---- Review 11-06: cover-shaped titles that are not covers -------------------------

    @Test
    fun anUndatedBracketAloneIsNotACover() {
        CoverTitles.knownSingers = { emptySet() }
        listOf(
            "[MV] IU(아이유) _ Palette(팔레트)" to "1theK",
            "BTS(방탄소년단) - 쩔어 (Dope)" to "1theK",
            "아이유 - 밤편지 (뮤직비디오)" to "1theK",
            "Ed Sheeran - Perfect (Music Video)" to "1theK",
            "Queen - Bohemian Rhapsody (Remastered 2011)" to "1theK",
            "I Don't Care (with Justin Bieber)" to "1theK",
            "Ed Sheeran - I Don't Care (with Justin Bieber)" to "1theK",
            "IU - 밤편지 (IU)" to "IU",
            "IU - 밤편지 (IU)" to "IU Official",
        ).forEach { (title, channel) ->
            assertNull(CoverTitles.read(title, channel, null), title)
        }
    }

    @Test
    fun anUndatedBracketThatNamesTheOriginalIsStillACredit() {
        CoverTitles.knownSingers = { emptySet() }
        listOf("IU", "Mr. Big").forEach { artist ->
            val read = cover("Singer - Song ($artist)", "channel")
            assertEquals(artist, read.originalArtist, artist)
        }
        // A romanised name in all caps still names the original after a Hangul title.
        assertEquals("IU", cover("가수 - 밤편지(IU)", "1theK").originalArtist)
    }

    @Test
    fun aLeadingBracketThatDescribesTheUploadIsNotASinger() {
        CoverTitles.knownSingers = { emptySet() }
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }
        listOf(
            "[도깨비 OST] 찬열, 펀치 - Stay With Me",
            "[Special Video] 아이유 - 밤편지",
            "[Official] 아이유 - 밤편지",
            "[Teaser] 아이유 - 밤편지",
        ).forEach { title ->
            assertNull(CoverTitles.read(title, "1theK", null), title)
            assertNull(CoverTitles.read(title, "1theK", null, unambiguousOnly = true), title)
        }
        assertTrue(told.isEmpty(), told.toString())
    }

    @Test
    fun aSlashProgramNameIsNotAnOriginalArtistAndIsNotLearned() {
        CoverTitles.knownSingers = { emptySet() }
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }
        assertNull(CoverTitles.read("YOASOBI - アイドル / THE FIRST TAKE", "channel", null))
        assertTrue(told.isEmpty(), told.toString())
    }

    @Test
    fun aSongThenArtistSlashOnAnOfficialChannelIsNotANewSinger() {
        CoverTitles.knownSingers = { emptySet() }
        val told = mutableListOf<String>()
        CoverTitles.onSingerSeen = { told += it }
        assertNull(CoverTitles.read("밤편지 / IU", "1theK", null))
        assertTrue(told.isEmpty(), told.toString())
    }

    @Test
    fun aChannelNameIsNotMatchedInsideAnotherWord() {
        CoverTitles.knownSingers = { emptySet() }
        // "the" inside "Another" must not make The Beatles the singer: the uploader sings it.
        val fan = cover("The Beatles - Yesterday Cover", "Another Voice")
        assertEquals("Yesterday", fan.title)
        assertEquals("The Beatles", fan.originalArtist)
        assertEquals("Another Voice", fan.singer)
        val own = cover("Song - 타비 Cover", "아라하시 타비")
        assertEquals("Song", own.title)
        assertEquals("타비", own.singer)
    }

    @Test
    fun aSongTitleEndingInCoverIsNotACoverTag() {
        CoverTitles.knownSingers = { emptySet() }
        assertNull(CoverTitles.read("Mr. Big - Take Cover", "Mr. Big", null))
        assertNull(CoverTitles.read("Run for Cover", "The Killers", null))
    }

    @Test
    fun aLeadingBracketSingerLeavesANamedOriginal() {
        val read = cover("[오화요] Ed Sheeran - Shape of You Cover", "레몬크림소스")
        assertEquals("Shape of You", read.title)
        assertEquals("Ed Sheeran", read.originalArtist)
        assertEquals("오화요", read.singer)
        // The channel's own romanised name is still the singer, not the original.
        val own = cover("[아카네 리제] Akane Lize - Song Cover", "아카네 리제 AKANE LIZE")
        assertEquals("Song", own.title)
        assertEquals("아카네 리제", own.singer)
        assertNull(own.originalArtist)
    }

    @Test
    fun aCoverWordKeepsTheOriginalCreditBesideIt() {
        val read = cover("오화요 - 밤편지 Cover (원곡 : 아이유)", "오화요")
        assertEquals("밤편지", read.title)
        assertEquals("아이유", read.originalArtist)
        assertEquals("오화요", read.singer)
    }

    @Test
    fun aNameBeginningWithMrIsNotAVersionWord() {
        val read = cover("innocent world - Mr.Children (오화요 Cover)", "channel")
        assertEquals("innocent world", read.title)
        assertEquals("Mr.Children", read.originalArtist)
        assertEquals("오화요", read.singer)
    }

    @Test
    fun anInstrumentBracketIsNotASinger() {
        CoverTitles.knownSingers = { emptySet() }
        listOf("바이올린", "Violin").forEach { instrument ->
            listOf("커버", "Cover").forEach { word ->
                assertNull(
                    CoverTitles.read("IU - 밤편지 ($instrument $word)", "channel", null),
                    "$instrument $word",
                )
            }
        }
    }

    @Test
    fun hangulBeforeAVersionOrOstMarkerStillCountsAsAMarker() {
        CoverTitles.knownSingers = { emptySet() }
        assertNull(CoverTitles.read("Singer - Song (원곡 : 도깨비OST)", "channel", null))
        assertNull(CoverTitles.read("Singer - Song (공식MV)", "channel", null))
        assertNull(CoverTitles.read("Singer - Song (원곡 : 공식MV)", "channel", null))
    }

    @Test
    fun originalCreditLabelsAndOrder() {
        CoverTitles.knownSingers = { emptySet() }
        val bySinger = cover("Singer - Song (원곡가수 : 아이유)", "channel")
        assertEquals("아이유", bySinger.originalArtist)
        val by = cover("Singer - Song (원곡 by Writer)", "channel")
        assertEquals("Writer", by.originalArtist)
        assertNull(CoverTitles.read("Singer - Song (원곡 ver.)", "channel", null))
    }

    @Test
    fun aCoverByTitleWithAnAllCapsArtistRunsSongFirst() {
        val read = cover("Drowning - WOODZ / cover by 오화요", "channel")
        assertEquals("Drowning", read.title)
        assertEquals("WOODZ", read.originalArtist)
        assertEquals("오화요", read.singer)
        assertTrue(read.orderUnsure)
    }

    @Test
    fun aStoredCoverWordMustBeAWholeWord() {
        assertFalse(CoverTitles.isCover(song("Song", "언더커버")))
    }

    @Test
    fun aLiveWordInTheSongTitleIsNotACoverTag() {
        val read = cover("Long Live Cover by X", "channel")
        assertEquals("Long Live", read.title)
        assertEquals("X", read.singer)
        assertNull(read.originalArtist)
    }
}
