package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A title that names a language version, and the sheet a catalogue answers
 * with, must agree. "Lethe (ENG cover)" was shown the Korean original's sheet
 * and aligned to it, because no such check existed.
 */
class LyricsVersionLanguageTest {

    private fun lines(vararg texts: String) = texts.mapIndexed { index, text ->
        LyricLine(timeMs = index * 1_000L, text = text)
    }

    private val english = lines(
        "I was a ghost, I was alone",
        "Gonna be, gonna be golden",
    )
    private val korean = lines(
        "사랑해 너를 영원히",
        "이 밤이 지나면 다시 만나",
    )
    private val japanese = lines(
        "沈むように溶けてゆくように",
        "夜に駆ける",
    )

    @Test
    fun aTitleNamesTheLanguageOfItsVersion() {
        assertEquals(LyricsAiGuard.Language.ENGLISH, LyricsAiGuard.namedLanguage("Lethe (ENG cover)"))
        assertEquals(LyricsAiGuard.Language.ENGLISH, LyricsAiGuard.namedLanguage("Lethe - English ver."))
        assertEquals(LyricsAiGuard.Language.JAPANESE, LyricsAiGuard.namedLanguage("Lethe (JP)"))
        assertEquals(LyricsAiGuard.Language.JAPANESE, LyricsAiGuard.namedLanguage("Lethe Japanese ver."))
        assertEquals(LyricsAiGuard.Language.JAPANESE, LyricsAiGuard.namedLanguage("Lethe 日本語ver."))
        assertEquals(LyricsAiGuard.Language.KOREAN, LyricsAiGuard.namedLanguage("Lethe Korean ver."))
        assertEquals(LyricsAiGuard.Language.KOREAN, LyricsAiGuard.namedLanguage("Lethe 한국어 ver."))
    }

    @Test
    fun anOrdinaryTitleNamesNoLanguage() {
        assertNull(LyricsAiGuard.namedLanguage("Lethe"))
        assertNull(LyricsAiGuard.namedLanguage("Englishman in New York"))
        // Two named languages cancel out instead of picking one.
        assertNull(LyricsAiGuard.namedLanguage("Lethe English ver. Korean ver."))
    }

    @Test
    fun theSheetIsReadByItsDominantScript() {
        assertEquals(LyricsAiGuard.Language.ENGLISH, LyricsAiGuard.dominantLanguage(english))
        assertEquals(LyricsAiGuard.Language.KOREAN, LyricsAiGuard.dominantLanguage(korean))
        assertEquals(LyricsAiGuard.Language.JAPANESE, LyricsAiGuard.dominantLanguage(japanese))
        // A Korean sheet with English hooks is still Korean.
        assertEquals(
            LyricsAiGuard.Language.KOREAN,
            LyricsAiGuard.dominantLanguage(lines("사랑해 너를 영원히", "I love you", "이 밤이 지나면")),
        )
        // Nothing sung, or no letters: no verdict.
        assertNull(LyricsAiGuard.dominantLanguage(lines("纯音乐，请欣赏")))
        assertNull(LyricsAiGuard.dominantLanguage(emptyList()))
    }

    @Test
    fun anEnglishCoverRejectsTheKoreanOriginalSheet() {
        assertTrue(LyricsAiGuard.scriptMismatch("Lethe (ENG cover)", korean))
        assertFalse(LyricsAiGuard.scriptMismatch("Lethe (ENG cover)", english))
        assertTrue(LyricsAiGuard.scriptMismatch("Lethe (JP ver.)", korean))
        assertFalse(LyricsAiGuard.scriptMismatch("Lethe (JP ver.)", japanese))
        assertTrue(LyricsAiGuard.scriptMismatch("Lethe Korean ver.", english))
        assertFalse(LyricsAiGuard.scriptMismatch("Lethe Korean ver.", korean))
    }

    @Test
    fun aTitleWithNoVersionTagIsNeverRejectedByScript() {
        assertFalse(LyricsAiGuard.scriptMismatch("Lethe", korean))
        assertFalse(LyricsAiGuard.scriptMismatch("Lethe", english))
        assertFalse(LyricsAiGuard.scriptMismatch("River Flows in You (Inst.)", english))
    }

    @Test
    fun anExplicitEnglishVersionStopsTheSongCountingAsKorean() {
        // The bug: the Korean artist credit made this song Korean, so Deepgram
        // was never asked and no transcript could expose the wrong sheet.
        assertFalse(
            LyricsAiGuard.isKoreanSong(
                "Lethe (ENG cover)",
                "한국 아티스트",
                english.map { it.text },
            ),
        )
        assertTrue(LyricsAiGuard.isKoreanSong("Lethe", "한국 아티스트", english.map { it.text }))
        assertTrue(LyricsAiGuard.isKoreanSong("사랑해", "아이유", EnglishTexts))
    }

    @Test
    fun aRejectedSheetIsRememberedPerVideoProviderAndText() {
        assertFalse(LyricsAiGuard.isScriptRejected("AMILJn51YBk", LyricsSource.KUGOU, korean))
        LyricsAiGuard.rememberScriptRejected("AMILJn51YBk", LyricsSource.KUGOU, korean)
        assertTrue(LyricsAiGuard.isScriptRejected("AMILJn51YBk", LyricsSource.KUGOU, korean))
        // A different provider or a different song is not the same rejection.
        assertFalse(LyricsAiGuard.isScriptRejected("AMILJn51YBk", LyricsSource.LRCLIB, korean))
        assertFalse(LyricsAiGuard.isScriptRejected("otherVideo", LyricsSource.KUGOU, korean))
    }

    private companion object {
        val EnglishTexts = listOf("I love you forever")
    }
}
