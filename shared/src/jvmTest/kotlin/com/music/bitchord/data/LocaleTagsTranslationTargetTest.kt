package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one place that decides which `tl` the translation requests are made
 * with. The player, the background prefetch and the lock screen must all ask
 * for the same destination, or a translation made ahead of time is filed under
 * a key the player never reads.
 */
class LocaleTagsTranslationTargetTest {

    @Test
    fun explicitChoiceWins() {
        assertEquals("ja", LocaleTags.translationTarget("ja", "en-US"))
        assertEquals("zh-TW", LocaleTags.translationTarget("zh-TW", "en-US"))
    }

    @Test
    fun blankChoiceFollowsTheAppLanguage() {
        assertEquals("ko", LocaleTags.translationTarget("", "ko-KR"))
        assertEquals("en", LocaleTags.translationTarget("", "en-US"))
        assertEquals("en", LocaleTags.translationTarget("", ""))
        assertEquals("he", LocaleTags.translationTarget("", "iw-IL"))
    }

    @Test
    fun chineseKeepsItsScriptOnTheWire() {
        assertEquals("zh-TW", LocaleTags.translationTarget("", "zh-Hant-TW"))
        assertEquals("zh-TW", LocaleTags.translationTarget("", "zh-HK"))
        assertEquals("zh-CN", LocaleTags.translationTarget("", "zh-Hans-CN"))
        assertEquals("zh-CN", LocaleTags.translationTarget("", "zh"))
    }
}
