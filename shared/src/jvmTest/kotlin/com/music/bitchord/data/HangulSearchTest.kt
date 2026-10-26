package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HangulSearchTest {

    @Test
    fun initialConsonantsAreReadFromCompleteSyllables() {
        assertEquals("ㄴㅇㅇㅈㅇ", HangulSearch.initialConsonants("눈이 오잖아"))
        assertEquals("ㅇㅇㅇ", HangulSearch.initialConsonants("아이유"))
        assertEquals("ㅎㄱ", HangulSearch.initialConsonants("한글"))
    }

    @Test
    fun aTypedEnglishKeyboardLineBecomesItsKoreanWord() {
        assertEquals("아이유", HangulSearch.fromKeyboard("dkdldb"))
        assertEquals("아이유으", HangulSearch.fromKeyboard("dkdlbm"))
        assertEquals("안녕", HangulSearch.fromKeyboard("dkssud"))
        assertEquals("한글", HangulSearch.fromKeyboard("gksrmf"))
        assertEquals("값", HangulSearch.fromKeyboard("rkqt"))
        assertEquals("과", HangulSearch.fromKeyboard("rhk"))
    }

    @Test
    fun completeKoreanTextMatchesAcrossSpaces() {
        assertTrue(HangulSearch.matches("눈이", "눈이 오잖아"))
        assertTrue(HangulSearch.matches("눈이오", "눈이 오잖아"))
        assertTrue(HangulSearch.matches("오잖아", "눈이 오잖아"))
    }

    @Test
    fun initialConsonantsFindTheTitle() {
        assertTrue(HangulSearch.matches("ㄴㅇㅈ", "눈이 오잖아"))
        assertTrue(HangulSearch.matches("ㄴㅇㅇㅈㅇ", "눈이 오잖아"))
        assertTrue(HangulSearch.matches("ㅇㅇㅇ", "아이유"))
        assertFalse(HangulSearch.matches("ㄴㅇㅈ", "사랑"))
    }

    @Test
    fun englishIgnoresCaseAndSpaces() {
        assertTrue(HangulSearch.matches("shape of you", "Shape of You"))
        assertTrue(HangulSearch.matches("SHAPEOFYOU", "Shape of You"))
        assertTrue(HangulSearch.matches("  shape   of   you  ", "Shape of You"))
        assertFalse(HangulSearch.matches("shape of me", "Shape of You"))
    }

    @Test
    fun aKoreanKeyboardTypoFindsTheKoreanTitle() {
        assertTrue(HangulSearch.matches("dkdlbm", "아이유"))
        assertTrue(HangulSearch.matches("dkdlbm", "아이유 - 좋은 날"))
        assertTrue(HangulSearch.matches("dkssud", "안녕하세요"))
        assertFalse(HangulSearch.matches("dkdlbm", "아이오아이"))
    }

    @Test
    fun scoreRanksExactAndPrefixAboveLooseInitials() {
        val target = HangulSearch.prepared("눈이 오잖아")
        assertTrue(HangulSearch.score("눈이 오잖아", target) > HangulSearch.score("눈이", target))
        assertTrue(HangulSearch.score("눈이", target) > HangulSearch.score("ㄴㅇㅈ", target))
        assertEquals(0, HangulSearch.score("없는노래", target))
    }

    @Test
    fun preparedValuesAreReused() {
        val first = HangulSearch.prepared("아이유")
        val second = HangulSearch.prepared("아이유")
        assertTrue(first === second)
    }
}
