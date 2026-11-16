package com.music.bitchord.data.lyrics

import com.music.bitchord.data.lyrics.LyricsAiGuard.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** L7: version tags in Korean titles, and "MR" as a name rather than the karaoke tag. */
class LyricsAiGuardTagTest {

    @Test
    fun aChineseVersionIsNotReadAsKorean() {
        assertNull(LyricsAiGuard.namedLanguage("노래 (중국어 버전)"))
        assertNull(LyricsAiGuard.namedLanguage("노래 (외국어 ver.)"))
    }

    @Test
    fun koreanWordsForTheOtherVersionsAreRead() {
        assertEquals(Language.JAPANESE, LyricsAiGuard.namedLanguage("노래 (일본어 ver.)"))
        assertEquals(Language.JAPANESE, LyricsAiGuard.namedLanguage("노래 (일어 버전)"))
        assertEquals(Language.ENGLISH, LyricsAiGuard.namedLanguage("노래 (영어 버전)"))
        assertEquals(Language.KOREAN, LyricsAiGuard.namedLanguage("Song (한국어 ver.)"))
        assertEquals(Language.KOREAN, LyricsAiGuard.namedLanguage("Song (국어 ver.)"))
    }

    @Test
    fun ordinaryWordsAreNotTags() {
        assertNull(LyricsAiGuard.namedLanguage("일어나"))
        assertNull(LyricsAiGuard.namedLanguage("영어로 말해줘"))
    }

    @Test
    fun aMisterInCapitalsIsATitle() {
        assertFalse(LyricsAiGuard.isInstrumentalTitle("THE KILLERS - MR. BRIGHTSIDE"))
        assertFalse(LyricsAiGuard.isInstrumentalTitle("MR. BLUE SKY"))
    }

    @Test
    fun theKaraokeTagStillCounts() {
        assertTrue(LyricsAiGuard.isInstrumentalTitle("노래 MR"))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("노래 (MR)"))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("노래 MR ver."))
        assertTrue(LyricsAiGuard.isInstrumentalTitle("노래 MR제거"))
    }
}
