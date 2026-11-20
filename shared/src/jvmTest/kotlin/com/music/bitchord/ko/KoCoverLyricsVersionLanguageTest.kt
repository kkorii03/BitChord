package com.music.bitchord.ko

import com.music.bitchord.data.cover.CoverTitles
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

    /**
     * Review of 759e28305: the screen's song check and refit, and every AI timing
     * (placeWithAi), build their Song from the lyrics query alone, as LyricsController
     * does from LyricsRequest: Sltk38pDXV8 arrives as ("스노우 하레이션", "μ's"), no
     * originalTitle, no coverSinger, no "커버" byline, isVideo false on the song check.
     * That no longer reads as a cover, so the listen stayed in the sheet's "ja" on the
     * path that applied the wrong sheet at 75% (17:59:59, a #alt quick listen in askAi).
     */
    @Test
    fun theQueryShapedSongTheChecksBuildIsHeardInKoreanOnceItsRowWasLoaded() {
        val singers = CoverTitles.knownSingers
        val original = CoverTitles.knownOriginal
        KoSungLanguage.forgetNotedForTest()
        try {
            CoverTitles.knownSingers = { emptySet() }
            CoverTitles.knownOriginal = { id -> if (id == "Sltk38pDXV8") "Snow halation" to "μ's" else null }
            for ((id, upload) in listOf(
                "Sltk38pDXV8" to "스노우 하레이션 - 타비 Cover(04.02)",
                "oZ4Tr4bINNY" to "히어로 - 타비 Cover(05.13)",
            )) {
                // As the queue holds it (CoverTitles.applyTo), the row loadLyricsFor gets.
                val row = CoverTitles.applyTo(
                    Song(videoId = id, title = upload, artist = "아라하시 타비", thumbnailUrl = null, isVideo = true),
                )
                assertTrue(CoverTitles.isCover(row), id)
                val query = CoverTitles.lyricsQuery(row)
                fun built(isVideo: Boolean) =
                    Song(videoId = id, title = query.title, artist = query.artist, thumbnailUrl = null, isVideo = isVideo)
                val songCheck = built(isVideo = false) // LyricsController.runSongCheck
                val aiTiming = built(isVideo = query.isVideo) // placeWithAi -> askAi, refit
                // 759e28305: the query alone is no cover, so the sheet's Japanese stood.
                assertFalse(CoverTitles.isCover(songCheck), id)
                assertEquals("ja", KoSungLanguage.listenLanguage("ja", songCheck), id)
                KoSungLanguage.noteRow(row) // LyricsController.loadLyricsFor / next-prep / TranslationAhead
                assertEquals("ko", KoSungLanguage.listenLanguage("ja", songCheck), id)
                assertEquals("ko", KoSungLanguage.listenLanguage("ja", aiTiming), id)
                assertEquals("ko", KoSungLanguage.listenLanguage("zh", songCheck), id)
                // Still only a Japanese/Chinese sheet is moved.
                assertEquals("en", KoSungLanguage.listenLanguage("en", songCheck), id)
                assertEquals("ko", KoSungLanguage.listenLanguage("ko", songCheck), id)
            }
            // A row that is not a Korean-titled cover is not noted.
            val japaneseCover = CoverTitles.applyTo(
                Song(videoId = "jp-cover", title = "夜に駆ける - 타비 Cover(01.01)", artist = "아라하시 타비", thumbnailUrl = null, isVideo = true),
            )
            KoSungLanguage.noteRow(japaneseCover)
            KoSungLanguage.noteRow(Song(videoId = "jp-original", title = "밤을 달리다", artist = "YOASOBI", thumbnailUrl = null, isVideo = false))
            for (id in listOf("jp-cover", "jp-original", "never-loaded")) {
                val bare = Song(videoId = id, title = "히어로", artist = "타비", thumbnailUrl = null, isVideo = false)
                assertEquals("ja", KoSungLanguage.listenLanguage("ja", bare), id)
            }
        } finally {
            CoverTitles.knownSingers = singers
            CoverTitles.knownOriginal = original
            KoSungLanguage.forgetNotedForTest()
        }
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
