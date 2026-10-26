package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** The script-majority and English-word rules Deepgram uses to pick a language. */
class DeepgramLanguageTest {

    @Test
    fun nothingToReadIsMultilingual() {
        assertEquals("multi", Deepgram.languageFor(emptyList()))
        assertEquals("multi", Deepgram.languageFor(listOf("")))
        assertEquals("multi", Deepgram.languageFor(listOf("   ")))
        assertEquals("multi", Deepgram.languageFor(listOf("123 !!! ...")))
    }

    @Test
    fun oneScriptAloneNamesTheLanguage() {
        assertEquals("ko", Deepgram.languageFor(listOf("사랑해")))
        assertEquals("ja", Deepgram.languageFor(listOf("あいうえお")))
        assertEquals("zh", Deepgram.languageFor(listOf("我爱你")))
    }

    @Test
    fun hangulNeedsAQuarterOfTheLetters() {
        assertEquals("ko", Deepgram.languageFor(listOf("한xxx")))
        assertEquals("multi", Deepgram.languageFor(listOf("한xxxx")))
    }

    @Test
    fun kanaNeedsATenthOfTheLetters() {
        assertEquals("ja", Deepgram.languageFor(listOf("あxxxxxxxxx")))
        assertEquals("multi", Deepgram.languageFor(listOf("あxxxxxxxxxx")))
    }

    @Test
    fun hanNeedsHalfOfTheLetters() {
        assertEquals("zh", Deepgram.languageFor(listOf("愛x")))
        assertEquals("multi", Deepgram.languageFor(listOf("愛xx")))
    }

    @Test
    fun hangulIsCheckedBeforeHan() {
        assertEquals("ko", Deepgram.languageFor(listOf("한愛xx")))
    }

    @Test
    fun kanaIsCheckedBeforeHan() {
        assertEquals("ja", Deepgram.languageFor(listOf("あ愛xxxxxxx")))
    }

    @Test
    fun japaneseKanaAndKanjiReadAsJapanese() {
        assertEquals("ja", Deepgram.languageFor(listOf("夜に駆ける")))
    }

    @Test
    fun englishNeedsEightWordsAndTwoCommonOnes() {
        assertEquals("en", Deepgram.languageFor(listOf("love you x x x x x x")))
        assertEquals("multi", Deepgram.languageFor(listOf("love x x x x x x x")))
        assertEquals("en", Deepgram.languageFor(listOf("the you i me my your and to")))
        assertEquals("multi", Deepgram.languageFor(listOf("the you i me my your and")))
    }

    @Test
    fun apostrophesInEnglishWordsAreKept() {
        assertEquals("en", Deepgram.languageFor(listOf("don't i'm it's x x x x x")))
    }

    @Test
    fun uppercaseEnglishIsEnglish() {
        assertEquals("en", Deepgram.languageFor(listOf("THE YOU I ME MY YOUR AND TO")))
    }

    @Test
    fun latinLyricsThatAreNotEnglishAreMultilingual() {
        assertEquals("multi", Deepgram.languageFor(listOf("hola amigo como estas mi hermano querido siempre")))
    }

    @Test
    fun hangulInAMixedSongWinsWhenItPassesTheQuarter() {
        assertEquals("ko", Deepgram.languageFor(listOf("사랑해 love you")))
    }
}
