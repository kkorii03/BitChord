package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.ArtistNameIndex
import com.music.bitchord.data.model.ArtistRef
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The names one song is looked up under: the listing's packaging has to come
 * off the title, the byline has to become the names a catalogue files it under,
 * and there must never be an unbounded pile of retries.
 */
class LyricsQueryVariantsTest {

    @BeforeTest
    fun clearIndex() = ArtistNameIndex.forget()

    @AfterTest
    fun forgetIndex() = ArtistNameIndex.forget()

    @Test
    fun aTranslationPairSearchesBothLanguages() {
        val names = LyricsQueryVariants.names("3.여수밤바다 - Yeosu Night Sea", "장범준")
        assertEquals("여수밤바다", names.first().title)
        assertEquals("장범준", names.first().artist)
        assertTrue(names.any { it.title == "yeosu night sea" && it.artist == "장범준" })
    }

    @Test
    fun productionCreditsAndTrackNumbersAreNotPartOfTheTitle() {
        val names = LyricsQueryVariants.names("03 눈 (Prod. by 기리보이)", "강현준(Lil tachi)")
        assertEquals("눈", names.first().title)
        assertEquals("강현준", names.first().artist)
        assertFalse(names.any { it.title.contains("prod", ignoreCase = true) })
        assertFalse(names.any { it.title.startsWith("03") })
    }

    @Test
    fun aCollaborationTriesThePrintedBylineAndItsNames() {
        val names = LyricsQueryVariants.names(
            "눈 (Prod. by 기리보이)",
            "강현준(Lil tachi) 및 김호진(HOTCHKISS)",
        )
        assertEquals("강현준", names.first().artist)
        assertTrue(names.any { it.artist == "강현준, lil tachi, 김호진, hotchkiss" })
    }

    @Test
    fun artistAliasesGivenByTheCallerAreSearched() {
        val names = LyricsQueryVariants.names("Song", "Solo", artistAliases = listOf("Alias"))
        assertEquals("solo", names.first().artist)
        assertTrue(names.any { it.artist == "solo, alias" })
    }

    @Test
    fun anAlbumsCreditedArtistsAreEvidenceForAnotherName() {
        ArtistNameIndex.record(listOf(ArtistRef("Group", "group-id")), albumName = "Shared Album")
        val names = LyricsQueryVariants.names("Song", "Solo", album = "Shared Album")
        assertTrue(names.any { it.artist == "solo, group" })
    }

    @Test
    fun aCompilationCreditedToEveryoneIsNotAnAliasSource() {
        ArtistNameIndex.record(
            listOf(ArtistRef("A", "a"), ArtistRef("B", "b"), ArtistRef("C", "c"), ArtistRef("D", "d")),
            albumName = "Compilation",
        )
        val names = LyricsQueryVariants.names("Song", "Solo", album = "Compilation")
        assertFalse(names.any { it.artist.contains("a, b, c, d") })
    }

    @Test
    fun theSearchNeverGrowsPastFourNames() {
        val names = LyricsQueryVariants.names(
            "여수밤바다 - Yeosu Night Sea",
            "A, B, C, D, E, F, G, H, I, J",
        )
        assertEquals(4, names.size)
        assertEquals("여수밤바다", names.first().title)
        assertEquals("a", names.first().artist)
    }

    @Test
    fun theArtistAloneIsStillTriedWhenTheTitleIsUnknown() {
        val names = LyricsQueryVariants.names("Song", "")
        assertEquals(listOf(LyricsQueryVariants.Name("song", "")), names)
    }

    @Test
    fun anEmptyTitleHasNothingToAsk() {
        assertTrue(LyricsQueryVariants.names("", "IU").isEmpty())
        assertTrue(LyricsQueryVariants.names("   ", "IU").isEmpty())
    }

    @Test
    fun aVersionMarkerStaysInTheSearchedTitle() {
        val names = LyricsQueryVariants.names("Song (Live)", "IU")
        assertEquals("song live", names.first().title)
    }

    @Test
    fun aFeaturingCreditIsNotPartOfTheTitle() {
        val names = LyricsQueryVariants.names("Song (feat. Someone)", "IU")
        assertEquals("song", names.first().title)
    }

    @Test
    fun anArtistDashTitleIsReadAsArtistAndSong() {
        val names = LyricsQueryVariants.names("IU - 밤편지", "IU")
        assertEquals("밤편지", names.first().title)
        assertEquals("iu", names.first().artist)
    }

    /**
     * The " - Topic" suffix is trimmed from every alias before the first name
     * is chosen, so the raw byline cannot shadow the cleaned fallback.
     */
    @Test
    fun aTopicChannelSuffixIsNotPartOfTheArtistName() {
        val names = LyricsQueryVariants.names("Song", "IU - Topic")
        assertEquals("iu", names.first().artist)
    }
}
