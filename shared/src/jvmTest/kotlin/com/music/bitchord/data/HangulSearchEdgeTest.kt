package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Boundary and odd-input coverage for the pure Hangul search helpers. These are
 * the pieces every search field relies on, so a change here silently changes
 * what the listener can find.
 */
class HangulSearchEdgeTest {

    @Test
    fun blankQueriesNeverMatch() {
        assertFalse(HangulSearch.matches("", "눈이 오잖아"))
        assertFalse(HangulSearch.matches("   ", "눈이 오잖아"))
        assertFalse(HangulSearch.matches("\t\n", "Shape of You"))
        assertEquals(0, HangulSearch.score("", "눈이 오잖아"))
        assertEquals(0, HangulSearch.score("   ", "눈이 오잖아"))
    }

    @Test
    fun nonHangulTextHasNoInitials() {
        assertEquals("", HangulSearch.initialConsonants(""))
        assertEquals("", HangulSearch.initialConsonants("Shape of You"))
        assertEquals("", HangulSearch.initialConsonants("123 !@#"))
    }

    @Test
    fun standaloneJamoAreTheirOwnInitials() {
        assertEquals("ㄱㄴ", HangulSearch.initialConsonants("ㄱㄴ"))
        assertEquals("ㄳㅄ", HangulSearch.initialConsonants("ㄳㅄ"))
        assertEquals("ㄱ", HangulSearch.initialConsonants("ㄱㅏ"))
    }

    @Test
    fun syllableBoundariesReadTheRightInitial() {
        assertEquals("ㄱ", HangulSearch.initialConsonants("가"))
        assertEquals("ㅎ", HangulSearch.initialConsonants("힣"))
        assertEquals("ㄱㅎ", HangulSearch.initialConsonants("가힣"))
    }

    @Test
    fun mixedTextKeepsOnlyHangulInitials() {
        assertEquals("ㄴㅇ", HangulSearch.initialConsonants("눈a이!"))
        assertEquals("ㅇㅇㅇ", HangulSearch.initialConsonants("아이유 2026"))
    }

    @Test
    fun uppercaseKeyboardLettersUseShiftJamo() {
        assertEquals("ㄲ", HangulSearch.fromKeyboard("R"))
        assertEquals("ㄸ", HangulSearch.fromKeyboard("E"))
        assertEquals("ㅃ", HangulSearch.fromKeyboard("Q"))
        assertEquals("ㅉ", HangulSearch.fromKeyboard("W"))
        assertEquals("ㅆ", HangulSearch.fromKeyboard("T"))
        assertEquals("얘", HangulSearch.fromKeyboard("O"))
        assertEquals("예", HangulSearch.fromKeyboard("P"))
        assertEquals("깞", HangulSearch.fromKeyboard("Rkqt"))
    }

    @Test
    fun compoundVowelsAndFinalsCompose() {
        assertEquals("값", HangulSearch.fromKeyboard("rkqt"))
        assertEquals("갃", HangulSearch.fromKeyboard("rkrt"))
        assertEquals("갉", HangulSearch.fromKeyboard("rkfr"))
        assertEquals("갌", HangulSearch.fromKeyboard("rkft"))
        assertEquals("괴", HangulSearch.fromKeyboard("rhl"))
        assertEquals("의", HangulSearch.fromKeyboard("dml"))
        assertEquals("궤", HangulSearch.fromKeyboard("rnp"))
        assertEquals("궈", HangulSearch.fromKeyboard("rnj"))
    }

    @Test
    fun unmappedCharactersPassThroughUnchanged() {
        assertEquals("이 바", HangulSearch.fromKeyboard("dl qk"))
        assertEquals("1", HangulSearch.fromKeyboard("1"))
        assertEquals("", HangulSearch.fromKeyboard(""))
        assertEquals("방", HangulSearch.fromKeyboard("qkd"))
        assertEquals("볼", HangulSearch.fromKeyboard("qhf"))
        assertEquals("ㅋ", HangulSearch.fromKeyboard("z"))
    }

    @Test
    fun aCompleteQueryMatchesAcrossSpaces() {
        assertTrue(HangulSearch.matches("눈이오잖아", "눈이 오잖아"))
        assertTrue(HangulSearch.matches("  눈이   오잖아  ", "눈이 오잖아"))
        assertFalse(HangulSearch.matches("눈이오잖아", "눈이 오지 않아"))
    }

    @Test
    fun initialsMatchWithSpacesAndJamoTargets() {
        assertTrue(HangulSearch.matches("ㄱ ㄴ", "가나다"))
        assertTrue(HangulSearch.matches("ㄱㄴ", "ㄱㄴ"))
        assertTrue(HangulSearch.matches("ㄴㅇㅈ", "눈이 오잖아"))
        assertFalse(HangulSearch.matches("ㄲ", "가나다"))
        assertFalse(HangulSearch.matches("ㄱ", "abc"))
    }

    @Test
    fun keyboardTypingFindsCompoundWords() {
        assertTrue(HangulSearch.matches("rkqt", "값"))
        assertTrue(HangulSearch.matches("rkqt", "값이 비싸다"))
        assertTrue(HangulSearch.matches("dkdlbm", "아이유"))
        assertFalse(HangulSearch.matches("rkqt", "가"))
    }

    @Test
    fun scoreConstantsStayStable() {
        val target = HangulSearch.prepared("눈이 오잖아")
        assertEquals(2420, HangulSearch.score("눈이 오잖아", target))
        assertEquals(1420, HangulSearch.score("눈이", target))
        assertEquals(590, HangulSearch.score("ㄴㅇㅈ", target))
        assertEquals(560, HangulSearch.score("ㄴㅈ", target))
        assertEquals(820, HangulSearch.score("dkdlbm", HangulSearch.prepared("아이유")))
        assertEquals(0, HangulSearch.score("없는노래", target))
    }

    @Test
    fun preparedFieldsNormalizeText() {
        val korean = HangulSearch.prepared("눈이 오잖아")
        assertEquals("눈이 오잖아", korean.text)
        assertEquals("눈이오잖아", korean.compact)
        assertEquals("ㄴㅇㅇㅈㅇ", korean.initials)
        assertEquals("눈이오잖아", korean.keyboardCompact)
        assertEquals("ㄴㅇㅇㅈㅇ", korean.keyboardInitials)

        val english = HangulSearch.prepared("Shape of You")
        assertEquals("shape of you", english.text)
        assertEquals("shapeofyou", english.compact)
        assertEquals("", english.initials)
    }

    @Test
    fun preparedCacheReturnsTheSameInstanceAndKeepsDistinctTextsApart() {
        val first = HangulSearch.prepared("아이유")
        val second = HangulSearch.prepared("아이유")
        val trailingSpace = HangulSearch.prepared("아이유 ")

        assertSame(first, second)
        assertNotSame(first, trailingSpace)
        assertEquals(first.compact, trailingSpace.compact)
    }
}
