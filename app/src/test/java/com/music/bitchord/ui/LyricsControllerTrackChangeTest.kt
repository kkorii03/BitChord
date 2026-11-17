package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review findings U1, U2, U4 (ship/review-1115/U.md): what one track left
 * behind must never land on, or stay on, another; and a sheet the listener
 * kept is never swapped out by a background answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerTrackChangeTest {

    private val job = SupervisorJob()
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var controller: LyricsController
    private lateinit var directory: File
    private lateinit var context: Context

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val savedSources = AppSettings.lyricsSources.value
    private val savedOrder = AppSettings.lyricsSourceOrder.value
    private val savedSynced = AppSettings.syncedLyrics.value
    private val savedRecheck = AppSettings.syncSafetyDownloadRecheck.value

    private fun sheet(tag: String): List<LyricLine> =
        List(6) { i -> LyricLine(5_000L + i * 3_000L, "$tag line ${i + 1}") }

    @Before
    fun setUp() {
        // One Main thread of its own, as LyricsControllerSheetChoiceTest does.
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lyrics-track-change-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        scope = CoroutineScope(job + mainDispatcher)
        AppSettings.syncedLyrics.value = true
        AppSettings.syncSafetyDownloadRecheck.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.LRCLIB)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.LRCLIB)
        directory = Files.createTempDirectory("bitchord-track-change").toFile()
        context = FakeContext.create(FakePreferences(), directory)
        storeField.set(LyricsTiming, null)
        val application = object : Application() {
            override fun getApplicationContext(): Context = context
            override fun getFilesDir(): File = context.filesDir
        }
        controller = LyricsController(application, scope)
    }

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        Dispatchers.resetMain()
        mainDispatcher.close()
        AppSettings.lyricsSources.value = savedSources
        AppSettings.lyricsSourceOrder.value = savedOrder
        AppSettings.syncedLyrics.value = savedSynced
        AppSettings.syncSafetyDownloadRecheck.value = savedRecheck
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    /** Runs [block] on the controller's own Main thread, as the app calls it. */
    private suspend fun onMain(block: () -> Unit) = withContext(mainDispatcher) { block() }

    // ---- U4 ----------------------------------------------------------------

    /**
     * The next row has no length yet (a video, or a track skipped to while
     * paused): the last track's lyrics must not stay on it, and its length
     * arriving still starts the one lookup.
     */
    @Test
    fun `a new track waiting for its length drops the last track's lyrics`() = runBlocking {
        val sheetA = sheet("first")
        val sheetB = sheet("second")
        val callsForB = AtomicInteger(0)
        controller.lyricsLookup = { videoId, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            when (videoId) {
                "u4-track-a" -> LyricsRepository.Result(source = LyricsSource.LRCLIB, lines = sheetA)
                "u4-track-b" -> {
                    callsForB.incrementAndGet()
                    LyricsRepository.Result(source = LyricsSource.LRCLIB, lines = sheetB)
                }
                else -> null
            }
        }

        onMain { controller.loadLyrics("u4-track-a", "First Song U4", "Nobody", durationMs = 200_000L) }
        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        assertEquals(sheetA.map { it.text }, controller.lyrics.value!!.map { it.text })

        onMain { controller.loadLyrics("u4-track-b", "Second Song U4", "Nobody", durationMs = 0L) }
        assertNull("the last track's lyrics stayed on the new one", controller.lyrics.value)
        assertNull(controller.lyricsSource.value)
        assertFalse(controller.lyricsChecked.value)
        assertEquals(0, callsForB.get())

        onMain { controller.loadLyrics("u4-track-b", "Second Song U4", "Nobody", durationMs = 210_000L) }
        withTimeout(10_000L) { controller.lyrics.first { it != null && it.first().text == sheetB.first().text } }
        assertTrue(controller.lyricsChecked.value)
        assertEquals(1, callsForB.get())
    }

    // ---- U2 ----------------------------------------------------------------

    /**
     * A sheet the listener kept for this song came through no lookup, so
     * nothing set its result; a background answer for the same id (here a
     * transcription that finished after its limit) replaced it outright.
     */
    @Test
    fun `a background upgrade does not replace the listener's kept sheet`() = runBlocking {
        val videoId = "u2-kept-sheet"
        val kept = sheet("kept")
        val late = listOf(LyricLine(timeMs = 0L, text = "written down late for U2"))
        val lookups = AtomicInteger(0)
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            lookups.incrementAndGet()
            null
        }
        assertTrue(
            LyricsOverrideStore.write(context, videoId, kept, LyricsSource.LRCLIB, LyricsOverrideFile.Kind.USER),
        )

        onMain { controller.loadLyrics(videoId, "Kept Sheet U2", "Nobody", durationMs = 215_000L) }
        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        assertEquals(kept.map { it.text }, controller.lyrics.value!!.map { it.text })
        assertEquals(0, lookups.get())

        val previousTranscriber = LyricsRepository.transcriber
        val previousLimit = LyricsRepository.transcribeLimitMs
        LyricsRepository.transcribeLimitMs = 100L
        LyricsRepository.transcriber = { _, _ ->
            delay(500L)
            late
        }
        try {
            // Subscribed before the emission, so the test knows the upgrade was offered.
            val offered = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000L) { LyricsRepository.upgrades.first { it.first == videoId } }
            }
            assertNull(
                LyricsRepository.lyrics(
                    videoId = videoId,
                    title = "Kept Sheet U2",
                    artist = "Nobody",
                    durationMs = 215_000L,
                    sources = setOf(LyricsSource.SIMP_MUSIC),
                    order = listOf(LyricsSource.SIMP_MUSIC),
                    transcribe = true,
                ),
            )
            assertEquals(late.map { it.text }, offered.await().second.lines.map { it.text })
            // The controller subscribed first: its collector already ran (or is queued
            // ahead of this) on Main, and publishLyrics writes without suspending.
            onMain { }
            delay(300L)
            assertEquals(
                "the kept sheet was replaced by a background answer",
                kept.map { it.text },
                controller.lyrics.value!!.map { it.text },
            )
        } finally {
            LyricsRepository.transcriber = previousTranscriber
            LyricsRepository.transcribeLimitMs = previousLimit
        }
    }

    // ---- U1 ----------------------------------------------------------------

    /**
     * The word alignment runs off the main thread for hundreds of ms; a skip
     * (or a new sheet) meanwhile must not get the lines it rebuilt.
     */
    @Test
    fun `a timing rebuild that finishes after the track changed is dropped`() = runBlocking {
        val lines = sheet("rebuilt")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val result = async(Dispatchers.Default) {
            rebuildTimedLines(lines, "timing", { given, _ ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                given.map { LyricLine(it.timeMs + 500L, it.text) }
            }) { current.get() }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        current.set(false) // the listener skipped while the lines were being rebuilt
        release.countDown()
        assertNull(result.await())
    }

    @Test
    fun `a timing rebuild for the lines still on screen is kept`() = runBlocking {
        val lines = sheet("rebuilt")
        val shown = rebuildTimedLines(lines, "timing", { given, _ ->
            given.map { LyricLine(it.timeMs + 500L, it.text) }
        }) { true }
        assertEquals(lines.map { it.timeMs + 500L }, shown!!.map { it.timeMs })
    }

    @Test
    fun `no timing shows the lines as their source timed them`() = runBlocking {
        val lines = sheet("plain")
        var applied = false
        val shown = rebuildTimedLines<String>(lines, null, { given, _ ->
            applied = true
            given
        }) { true }
        assertTrue(shown === lines)
        assertFalse(applied)
    }
}
