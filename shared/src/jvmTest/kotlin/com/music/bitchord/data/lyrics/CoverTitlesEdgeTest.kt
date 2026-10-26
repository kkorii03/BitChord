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
        assertFalse(CoverTitles.isCover(song("Song", "Singer 커버", isVideo = false)))
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
}
