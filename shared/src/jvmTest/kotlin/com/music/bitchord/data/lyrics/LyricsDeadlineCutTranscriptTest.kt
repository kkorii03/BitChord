package com.music.bitchord.data.lyrics

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review 11-21 FETCH-2: a search the 12 s deadline cut short was taken as "no lyrics
 * anywhere". The paid Gemini transcript that followed was stored as final (memory for
 * the session, disk checked again only after 24 h), so the provider sheet a slow
 * network had not delivered in time never replaced it. A deadline of 0 cuts the search
 * before any source is asked, so no source is contacted; the words are made up.
 */
class LyricsDeadlineCutTranscriptTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val sources = setOf(LyricsSource.SIMP_MUSIC)
    private val order = listOf(LyricsSource.SIMP_MUSIC)
    private val durationMs = 200_000L
    private val title = "Slow Network Song"

    private val lines = (0 until 6).map { LyricLine(5_000L + it * 5_000L, "made up heard line $it") }

    private suspend fun ask(videoId: String) = LyricsRepository.lyrics(
        videoId = videoId,
        title = title,
        artist = "Nobody",
        durationMs = durationMs,
        sources = sources,
        order = order,
        transcribe = true,
    )

    private fun key(videoId: String) = LyricsRepository.questionKey(
        videoId = videoId,
        title = title,
        artist = "Nobody",
        durationMs = durationMs,
        album = null,
        prioritizeSyllableSync = false,
        isrc = null,
        isVideo = false,
        artistKnown = true,
        alternatives = emptyList(),
        order = order,
        sources = sources,
    )

    private fun isolated(block: suspend (AtomicInteger) -> Unit) {
        val previousTranscriber = LyricsRepository.transcriber
        val previousKeep = LyricsTranslation.keepDir
        val previousHeard = LyricsRepository.heardWordsFor
        val previousFit = LyricsRepository.measuredFitFor
        val previousDeadline = LyricsRepository.lookupDeadlineMs
        val directory = Files.createTempDirectory("bitchord-deadline-cut").toFile()
        val transcribed = AtomicInteger()
        LyricsTranslation.keepDir = directory
        LyricsRepository.heardWordsFor = null
        LyricsRepository.measuredFitFor = null
        LyricsRepository.transcriber = { _, _ ->
            transcribed.incrementAndGet()
            lines
        }
        try {
            runBlocking { block(transcribed) }
        } finally {
            LyricsRepository.transcriber = previousTranscriber
            LyricsRepository.heardWordsFor = previousHeard
            LyricsRepository.measuredFitFor = previousFit
            LyricsRepository.lookupDeadlineMs = previousDeadline
            LyricsTranslation.keepDir = previousKeep
            directory.deleteRecursively()
        }
    }

    private suspend fun waitUntilChecked(videoId: String) = withTimeout(10_000L) {
        while (LyricsDiskCache.read(key(videoId))?.let(LyricsDiskCache::worthCheckingAgain) != false) delay(20L)
    }

    @Test
    fun aTranscriptAfterADeadlineCutIsKeptForARecheckNotAsFinal() = isolated { transcribed ->
        val videoId = "deadline-cut-transcript"
        LyricsRepository.lookupDeadlineMs = 0L
        val first = ask(videoId)
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, first?.source, "the screen still gets the AI sheet now")
        assertEquals(1, transcribed.get())

        // Old code: written with checkedAt = now, so no source was asked again for 24 h.
        val stored = assertNotNull(LyricsDiskCache.read(key(videoId)))
        assertTrue(LyricsDiskCache.worthCheckingAgain(stored), "a deadline-cut transcript must be checked again right away")

        // The next play, sources as slow / empty as before: the kept sheet at once, the
        // sources asked again in the background (old code: a session memo hit that
        // never asked anybody), and Gemini not paid for a second time.
        LyricsRepository.lookupDeadlineMs = LyricsRepository.LOOKUP_DEADLINE_MS
        val again = ask(videoId)
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, again?.source)
        waitUntilChecked(videoId)
        assertEquals(1, transcribed.get(), "the same transcript is served without a new Gemini call")
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, ask(videoId)?.source)
        assertEquals(1, transcribed.get())
    }

    @Test
    fun aKeptRecheckTranscriptAfterARestartIsServedWithoutTranscribingAgain() = isolated { transcribed ->
        // What the next day finds on disk: memory and the session's transcript are empty.
        val videoId = "deadline-cut-transcript-restart"
        LyricsDiskCache.write(key(videoId), LyricsRepository.Result(LyricsSource.GEMINI_TRANSCRIPT, lines), recheck = true)
        assertTrue(LyricsDiskCache.worthCheckingAgain(assertNotNull(LyricsDiskCache.read(key(videoId)))))

        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, ask(videoId)?.source)
        // The sources were asked again and still had nothing: the usual 24 h wait from now on.
        waitUntilChecked(videoId)
        assertEquals(0, transcribed.get(), "no second Gemini transcription for the same question")
    }

    @Test
    fun aRealMissIsStoredAsFreshAsBefore() = isolated { transcribed ->
        // Every source answered (here: none to ask) well before the deadline.
        val videoId = "real-miss-transcript"
        LyricsRepository.lookupDeadlineMs = LyricsRepository.LOOKUP_DEADLINE_MS
        assertEquals(LyricsSource.GEMINI_TRANSCRIPT, ask(videoId)?.source)
        assertEquals(1, transcribed.get())
        val stored = assertNotNull(LyricsDiskCache.read(key(videoId)))
        assertFalse(stored.recheck)
        assertFalse(LyricsDiskCache.worthCheckingAgain(stored), "a real miss keeps its transcript for the usual 24 h")
    }
}
