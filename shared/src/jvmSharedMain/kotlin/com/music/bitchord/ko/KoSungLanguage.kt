package com.music.bitchord.ko

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricsAiGuard
import com.music.bitchord.data.model.Song
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * [KO] coverlang: the language a Korean-titled cover is sung in, for the two
 * places that otherwise took it from the original song.
 *
 * Phone 11-19 (Tabi covers 히어로 oZ4Tr4bINNY, 스노우 하레이션 Sltk38pDXV8): Gemini,
 * fetching the video by URL, wrote the Japanese original's lyrics for a cover
 * sung in Korean. The start listen heard Korean and refused that sheet, but the
 * sync listen took its language from the sheet's script (`song-hear: sent
 * lang=ja`): Whisper forced into Japanese on Korean singing wrote Japanese words
 * close enough to the original's that the wrong sheet was "applied" at 75%
 * (17:59:59 `song-sync: Sltk38pDXV8 result=applied match=75% … lang=ja`).
 */
object KoSungLanguage {

    /**
     * Whether [title] says it is sung in Korean: a 한국어/Korean ver. tag, or a
     * title written mostly in Hangul with no other language named. The same
     * script rule [Deepgram.languageFor] gives the start listen ("ko").
     */
    fun titleSaysKorean(title: String): Boolean {
        if (title.isBlank()) return false
        val named = LyricsAiGuard.namedLanguage(title)
        if (named != null) return named == LyricsAiGuard.Language.KOREAN
        return Deepgram.languageFor(listOf(title)) == "ko"
    }

    /**
     * The language [song]'s sheet is listened to in. A cover whose title is
     * Korean is heard in Korean when its sheet reads as Japanese or Chinese: a
     * Korean-language cover of a J-pop/C-pop song is the case, and that sheet is
     * the original's, so only Korean words can tell it is not what is sung. A
     * Korean sheet, an English/Latin one (Korean covers of English songs are
     * mostly sung in English) and every non-cover row keep the sheet's language.
     */
    fun listenLanguage(sheetLanguage: String, song: Song): String =
        if ((sheetLanguage == "ja" || sheetLanguage == "zh") &&
            titleSaysKorean(song.title) && CoverTitles.isCover(song)
        ) {
            "ko"
        } else {
            sheetLanguage
        }

    /**
     * Told to Gemini beside the video when its title is Korean. Not "it is
     * Korean": a Korean-titled upload may still be sung in Japanese, and the
     * instruction stays "write what you hear". What it forbids is the 11-19
     * failure, the original release's lyrics written for a Korean cover.
     */
    fun transcribeHint(title: String): String? =
        if (titleSaysKorean(title)) TRANSCRIBE_KOREAN_TITLE else null

    internal const val TRANSCRIBE_KOREAN_TITLE =
        "The video's title is written in Korean. If the singing is in Korean, as in a Korean-language cover " +
            "of a song first released in another language, write the Korean words that are actually sung, " +
            "never the original release's lyrics. If you hear another language, write that language."

    /** The row title a transcription was asked for, carried to the app's transcriber in its coroutine context. */
    class RowTitle(val title: String) : AbstractCoroutineContextElement(RowTitle) {
        companion object Key : CoroutineContext.Key<RowTitle>
    }
}
