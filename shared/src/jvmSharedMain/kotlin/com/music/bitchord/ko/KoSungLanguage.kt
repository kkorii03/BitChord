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
     * Korean, or whose align-farm file is Korean ([noteFarm]), is heard in
     * Korean when its sheet reads as Japanese or Chinese: a
     * Korean-language cover of a J-pop/C-pop song is the case, and that sheet is
     * the original's, so only Korean words can tell it is not what is sung. A
     * Korean sheet, an English/Latin one (Korean covers of English songs are
     * mostly sung in English) and every non-cover row keep the sheet's language.
     */
    fun listenLanguage(sheetLanguage: String, song: Song): String =
        if ((sheetLanguage == "ja" || sheetLanguage == "zh") && (koreanTitled(song) || farmKorean(song))) {
            "ko"
        } else {
            sheetLanguage
        }

    /** Why [listenLanguage] moved [song] to Korean, for the log: its title, or the farm's words. */
    fun evidence(song: Song): String = if (koreanTitled(song)) "Korean-titled" else "farm-Korean"

    private fun koreanCover(song: Song): Boolean = titleSaysKorean(song.title) && CoverTitles.isCover(song)

    private fun koreanTitled(song: Song): Boolean = koreanCover(song) || noted(song.videoId)

    /**
     * [KO] coverahead: a cover whose align-farm file is Korean. The farm places a Korean
     * sheet's words with a Korean model (bench/farm/farm.py), so its "ko" says the upload
     * was sung and scored in Korean. Phone 11-20 vc224, 오화요 covers with a Latin or
     * Japanese title: Last Stardust WANYQ3JC298 `align-farm: 100 words lang=ko asked ahead
     * for=ja`, then 4 MB sent in ja, 67%, refused; Fansa vRjfYdwM5L4 the same, 43%
     * (its emulator run applied a Korean sheet at 94% in ko). A row not known as a cover
     * keeps the sheet's language: a farm file is one scored sheet, not the singing itself.
     */
    private fun farmKorean(song: Song): Boolean =
        synchronized(farmSaidKorean) { farmSaidKorean.containsKey(song.videoId) } &&
            (CoverTitles.isCover(song) || notedCover(song.videoId))

    /**
     * Video ids of cover rows, noted where the real row is at hand ([noteRow]), true for
     * a Korean title. The screen's song check, refit and AI timing build their
     * Song from the lyrics query alone (title "스노우 하레이션", artist "μ's",
     * isVideo false), which no longer reads as a cover; the id carries it to them.
     * A video's title is fixed, so an entry never goes stale; bounded, oldest out.
     */
    private val covers = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > NOTED_MAX
    }

    /** Video ids whose align-farm file came back in Korean this run ([noteFarm]); bounded, oldest out. */
    private val farmSaidKorean = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > NOTED_MAX
    }
    private const val NOTED_MAX = 64

    /** Notes [row] (a queue row as the player has it) when it is a cover, and whether its title is Korean. */
    fun noteRow(row: Song) {
        if (row.videoId.isBlank() || !CoverTitles.isCover(row)) return
        val korean = titleSaysKorean(row.title)
        synchronized(covers) { covers[row.videoId] = korean }
    }

    /** Notes the language of [videoId]'s align-farm file, as the farm's answer arrives. */
    fun noteFarm(videoId: String, language: String) {
        if (videoId.isBlank()) return
        synchronized(farmSaidKorean) {
            if (language == "ko") farmSaidKorean[videoId] = Unit else farmSaidKorean.remove(videoId)
        }
    }

    private fun noted(videoId: String): Boolean = synchronized(covers) { covers[videoId] == true }

    private fun notedCover(videoId: String): Boolean = synchronized(covers) { covers.containsKey(videoId) }

    internal fun forgetNotedForTest() {
        synchronized(covers) { covers.clear() }
        synchronized(farmSaidKorean) { farmSaidKorean.clear() }
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
