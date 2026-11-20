package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * coverlang (phone 11-19, Tabi covers 히어로 oZ4Tr4bINNY and 스노우 하레이션 Sltk38pDXV8):
 * a Korean-sung cover of a Japanese song was written down by Gemini as the Japanese
 * original, and its sync listen ran in the sheet's Japanese (`song-hear: sent lang=ja`),
 * which "applied" the wrong sheet at 75%. On the old code the transcriber never saw
 * the row title and the listen always took the sheet's script. Words are made up;
 * no source is contacted (the only one allowed is hidden).
 */
class KoCoverLyricsVersionLanguageTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private fun video(title: String, artist: String) =
        Song(videoId = "cover-lang-test", title = title, artist = artist, thumbnailUrl = null, isVideo = true)

    @Test
    fun aKoreanTitledCoverWithAJapaneseSheetIsHeardInKorean() {
        val tabi = video("스노우 하레이션", "μ's · 타비 커버")
        // Old code: Deepgram.languageFor(sheet) alone, "ja".
        assertEquals("ko", KoSungLanguage.listenLanguage("ja", tabi))
        assertEquals("ko", KoSungLanguage.listenLanguage("zh", video("히어로", "타비 커버")))
        // A 한국어-tagged cover with a Latin title is Korean too.
        assertEquals("ko", KoSungLanguage.listenLanguage("ja", video("Snow halation (Korean ver.)", "타비 커버")))
    }

    @Test
    fun everyOtherRowKeepsTheSheetsLanguage() {
        val tabi = video("스노우 하레이션", "μ's · 타비 커버")
        assertEquals("ko", KoSungLanguage.listenLanguage("ko", tabi))
        // Korean covers of English songs are mostly sung in English.
        assertEquals("en", KoSungLanguage.listenLanguage("en", tabi))
        assertEquals("multi", KoSungLanguage.listenLanguage("multi", tabi))
        // A cover that names Japanese, or a Japanese title, is heard in Japanese.
        assertEquals("ja", KoSungLanguage.listenLanguage("ja", video("스노우 하레이션 (일본어)", "타비 커버")))
        assertEquals("ja", KoSungLanguage.listenLanguage("ja", video("夜に駆ける", "타비 커버")))
        // Not a cover: an uploaded original keeps its sheet's language whatever its title.
        assertEquals("ja", KoSungLanguage.listenLanguage("ja", video("밤을 달리다", "YOASOBI")))
    }

    @Test
    fun theTranscriptionIsToldOnlyAboutAKoreanTitle() {
        assertTrue(KoSungLanguage.titleSaysKorean("히어로"))
        assertTrue(KoSungLanguage.titleSaysKorean("Flowering (한국어 커버)"))
        assertFalse(KoSungLanguage.titleSaysKorean("Hero"))
        assertFalse(KoSungLanguage.titleSaysKorean("개화 (Japanese ver.)"))
        assertFalse(KoSungLanguage.titleSaysKorean(""))
        assertNotNull(KoSungLanguage.transcribeHint("개화"))
        assertNull(KoSungLanguage.transcribeHint("Flowering"))
    }

    @Test
    fun theTranscriberHearsTheRowTitleItWasAskedFor() {
        val previousTranscriber = LyricsRepository.transcriber
        val previousKeep = LyricsTranslation.keepDir
        val previousHeard = LyricsRepository.heardWordsFor
        val directory = Files.createTempDirectory("bitchord-cover-lang").toFile()
        LyricsTranslation.keepDir = directory
        LyricsRepository.heardWordsFor = null
        var asked: String? = null
        val lines = (0 until 6).map { LyricLine(5_000L + it * 5_000L, "made up heard line $it") }
        LyricsRepository.transcriber = { _, _ ->
            asked = currentCoroutineContext()[KoSungLanguage.RowTitle]?.title
            lines
        }
        try {
            val found = runBlocking {
                LyricsRepository.lyrics(
                    videoId = "cover-lang-row-title",
                    title = "히어로",
                    artist = "타비",
                    durationMs = 200_000L,
                    sources = setOf(LyricsSource.SIMP_MUSIC),
                    order = listOf(LyricsSource.SIMP_MUSIC),
                    isVideo = true,
                    transcribe = true,
                )
            }
            assertEquals(LyricsSource.GEMINI_TRANSCRIPT, found?.source)
            // Old code: the run had no title in its context, so Gemini got no hint.
            assertEquals("히어로", asked)
        } finally {
            LyricsRepository.transcriber = previousTranscriber
            LyricsRepository.heardWordsFor = previousHeard
            LyricsTranslation.keepDir = previousKeep
            directory.deleteRecursively()
        }
    }
}
