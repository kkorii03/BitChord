package com.music.bitchord.ui

import android.app.Application
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The controller, not just the gate, must withhold the lookup until the player
 * reports a length (a59AuyMjb68), and must stop waiting at its deadline with
 * whatever a provider already delivered instead of spinning forever
 * (MA9BrvtKvhM).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerRequestTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var controller: LyricsController

    private val savedSources = AppSettings.lyricsSources.value
    private val savedOrder = AppSettings.lyricsSourceOrder.value
    private val savedSynced = AppSettings.syncedLyrics.value

    private val lateLines = listOf(LyricLine(timeMs = 0L, text = "written down after the limit"))

    /** The AI's late answer, asked the way the screen's own lookup asks it. */
    private suspend fun lateTranscription(videoId: String): LyricsRepository.Result? =
        LyricsRepository.lyrics(
            videoId = videoId,
            title = "Late Sheet",
            artist = "Nobody",
            durationMs = 215_000L,
            sources = setOf(LyricsSource.SIMP_MUSIC),
            order = listOf(LyricsSource.SIMP_MUSIC),
            transcribe = true,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        AppSettings.syncedLyrics.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.LRCLIB)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.LRCLIB)
        controller = LyricsController(Application(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        AppSettings.lyricsSources.value = savedSources
        AppSettings.lyricsSourceOrder.value = savedOrder
        AppSettings.syncedLyrics.value = savedSynced
    }

    private suspend fun LyricsController.awaitChecked(timeoutMs: Long = 3_000L) {
        withTimeout(timeoutMs) { lyricsChecked.first { it } }
    }

    @Test
    fun aStreamWithNoLengthMakesNoRequestUntilTheLengthArrives() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Long>()
        val sheet = LyricsRepository.Result(
            source = LyricsSource.LRCLIB,
            lines = listOf(LyricLine(timeMs = 0L, text = "hello")),
        )
        controller.lyricsLookup = { _, _, _, duration, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            calls += 1
            entered.complete(duration)
            sheet
        }

        controller.loadLyrics("a59AuyMjb68", "A Song", "An Artist", durationMs = 0L)
        // Give an eager old lookup time to start: it must not.
        assertNull(withTimeoutOrNull(300L) { entered.await() })
        assertEquals(0, calls)

        controller.loadLyrics("a59AuyMjb68", "A Song", "An Artist", durationMs = 215_000L)
        assertEquals(215_000L, entered.await())
        controller.awaitChecked()
        assertEquals(1, calls)

        // The player repeating the event must not start the lookup again.
        controller.loadLyrics("a59AuyMjb68", "A Song", "An Artist", durationMs = 215_000L)
        controller.awaitChecked()
        assertEquals(1, calls)
    }

    @Test
    fun aProviderThatNeverReturnsStillEndsAtTheDeadlineWithWhatWasFound() = runBlocking {
        val sheet = LyricsRepository.Result(
            source = LyricsSource.LRCLIB,
            lines = listOf(LyricLine(timeMs = 0L, text = "hello")),
        )
        val entered = CompletableDeferred<Unit>()
        controller.lookupTimeoutMs = 100L
        controller.transcribeTimeoutMs = 0L
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            onResult?.invoke(LyricsSource.LRCLIB, sheet, null)
            entered.complete(Unit)
            awaitCancellation()
        }

        controller.loadLyrics("MA9BrvtKvhM", "A Song", "An Artist", durationMs = 215_000L)
        entered.await()
        controller.awaitChecked()

        assertTrue(controller.lyricsChecked.value)
        assertEquals(sheet.lines, controller.lyrics.value)
    }

    @Test
    fun aProviderThatNeverReturnsAndFoundNothingEndsWithNoLyrics() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        controller.lookupTimeoutMs = 100L
        controller.transcribeTimeoutMs = 0L
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }

        controller.loadLyrics("MA9BrvtKvhM", "A Song", "An Artist", durationMs = 215_000L)
        entered.await()
        controller.awaitChecked()

        assertTrue(controller.lyricsChecked.value)
        assertNull(controller.lyrics.value)
    }

    /**
     * The sheet the AI finished after the repository's limit is this track's:
     * it reaches the screen through the same publish path any sheet does.
     */
    @Test
    fun aLateTranscriptionIsShownForTheTrackStillPlaying() = runBlocking {
        val previousTranscriber = LyricsRepository.transcriber
        val previousLimit = LyricsRepository.transcribeLimitMs
        LyricsRepository.transcribeLimitMs = 100L
        LyricsRepository.transcriber = { _, _ ->
            delay(500L)
            lateLines
        }
        try {
            controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> null }
            controller.loadLyrics("late-sheet-current", "Late Sheet", "Nobody", durationMs = 215_000L)
            controller.awaitChecked()

            assertNull(async { lateTranscription("late-sheet-current") }.await())

            assertEquals(
                lateLines.map { it.text },
                withTimeout(5_000L) { controller.lyrics.first { it != null } }!!.map { it.text },
            )
            assertEquals(LyricsSource.GEMINI_TRANSCRIPT, controller.lyricsSource.value)
        } finally {
            LyricsRepository.transcriber = previousTranscriber
            LyricsRepository.transcribeLimitMs = previousLimit
        }
    }

    /**
     * A skip while the AI is still listening: the late sheet must never land
     * on the track that replaced it (the request video id is the check).
     */
    @Test
    fun aLateTranscriptionForASkippedTrackIsNotShown() = runBlocking {
        val previousTranscriber = LyricsRepository.transcriber
        val previousLimit = LyricsRepository.transcribeLimitMs
        LyricsRepository.transcribeLimitMs = 100L
        LyricsRepository.transcriber = { _, _ ->
            delay(500L)
            lateLines
        }
        try {
            controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> null }
            controller.loadLyrics("late-sheet-skipped", "Late Sheet", "Nobody", durationMs = 215_000L)
            controller.awaitChecked()
            controller.loadLyrics("another-track", "Other Song", "Nobody", durationMs = 200_000L)
            controller.awaitChecked()

            assertNull(async { lateTranscription("late-sheet-skipped") }.await())
            assertNull(
                withTimeoutOrNull(1_500L) { controller.lyrics.first { it != null } },
                "the late sheet must not be shown for the track that replaced it",
            )
        } finally {
            LyricsRepository.transcriber = previousTranscriber
            LyricsRepository.transcribeLimitMs = previousLimit
        }
    }
}
