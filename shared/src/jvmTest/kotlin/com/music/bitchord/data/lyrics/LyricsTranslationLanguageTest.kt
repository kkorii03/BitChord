package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which source/destination pairs count as "already in this language".
 *
 * Chinese is the one language where the script is part of what the user asked
 * for: zh-CN and zh-TW share a base language but not their writing, so their
 * translation must still run (and convert the script) instead of being reported
 * as "already in this language".
 */
class LyricsTranslationLanguageTest {

    @Test
    fun chineseScriptsAreNotTheSameDestination() {
        assertFalse(LyricsTranslation.sameLanguage("zh-CN", "zh-TW"))
        assertFalse(LyricsTranslation.sameLanguage("zh-TW", "zh-CN"))
        assertFalse(LyricsTranslation.sameLanguage("zh-Hans", "zh-TW"))
    }

    @Test
    fun chineseScriptsThatMatchStayUnchanged() {
        assertTrue(LyricsTranslation.sameLanguage("zh-TW", "zh-TW"))
        assertTrue(LyricsTranslation.sameLanguage("zh-Hant", "zh-TW"))
        assertTrue(LyricsTranslation.sameLanguage("zh-CN", "zh-Hans"))
    }

    @Test
    fun otherLanguagesStillCompareByBaseLanguage() {
        assertTrue(LyricsTranslation.sameLanguage("ko", "ko-KR"))
        assertTrue(LyricsTranslation.sameLanguage("iw", "he"))
        assertTrue(LyricsTranslation.sameLanguage("in", "id"))
        assertFalse(LyricsTranslation.sameLanguage("ko", "en"))
    }
}
