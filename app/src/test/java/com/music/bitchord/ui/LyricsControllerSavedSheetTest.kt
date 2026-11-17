package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ko.KoSavedLyrics
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * LYR6 (ship/review-1115/LYR6.md): a downloaded song's saved sheet ("저장된 가사") came before any
 * lookup on every play, so a wrong or badly timed one stayed (DyDfgMOUjCI 11-17: 14 s early while
 * the provider's sheet was corrected in 1.6 s). Online the providers decide; the saved copy is the
 * offline one, follows the sheet last seen or picked, and its old check does not carry over.
 * Sheets are made-up tokens, no lyric text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerSavedSheetTest {

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

    private val file = "file:///music/lyr6.flac"

    private fun sheet(tag: String, everyMs: Long = 4_000L): List<LyricLine> =
        List(12) { i -> LyricLine(5_000L + i * everyMs, "$tag${i + 1} q$tag r$tag") }

    private val saved = sheet("sv")
    private val fresh = sheet("fr")
    private val other = sheet("ot", 3_000L)
    private val otherNewer = sheet("on", 3_000L)

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lyrics-saved-sheet-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        scope = CoroutineScope(job + mainDispatcher)
        AppSettings.syncedLyrics.value = true
        AppSettings.syncSafetyDownloadRecheck.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.KUGOU, LyricsSource.VIBE)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.KUGOU, LyricsSource.VIBE)
        directory = Files.createTempDirectory("bitchord-saved-sheet").toFile()
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

    private fun openStore(json: String) {
        File(directory, "lyrics_timing_v1.json").writeText(json)
        val storeClass = Class.forName("com.music.bitchord.playback.smart.LyricsTiming\$Store")
        val ctor = storeClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        storeField.set(LyricsTiming, ctor.newInstance(File(directory, "lyrics_timing_v1.json")))
    }

    /** Both providers answer; [winner] is the lookup's own pick. */
    private fun answer(winner: LyricsSource, winnerLines: List<LyricLine>, loser: LyricsSource, loserLines: List<LyricLine>) {
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            onResult?.invoke(loser, LyricsRepository.Result(loser, loserLines), null)
            val won = LyricsRepository.Result(winner, winnerLines)
            onResult?.invoke(winner, won, null)
            won
        }
    }

    private suspend fun loadDownloaded() = withContext(mainDispatcher) {
        controller.loadLyrics("video", "Song", "Someone", 200_000L, localUri = file)
    }

    private suspend fun waitForSaved(check: (LyricsOverrideFile.Stored) -> Boolean): LyricsOverrideFile.Stored =
        withTimeout(10_000L) {
            var stored = LyricsOverrideStore.read(context, "video")
            while (stored == null || !check(stored)) {
                delay(50L)
                stored = LyricsOverrideStore.read(context, "video")
            }
            stored!!
        }

    @Test
    fun `online a downloaded song shows the provider's sheet and saves it over the old copy`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.VIBE)
        answer(LyricsSource.KUGOU, fresh, LyricsSource.VIBE, other)

        loadDownloaded()

        // Before LYR6 the saved sheet stayed, with no source ("저장된 가사"), and this timed out.
        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.KUGOU } }
        assertEquals(fresh.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
        val copy = waitForSaved { it.lines == fresh }
        assertEquals(LyricsSource.KUGOU, copy.source)
        assertEquals(LyricsOverrideFile.Kind.RECHECK, copy.kind)
    }

    @Test
    fun `choosing another sheet in the chooser makes it the saved copy`() = runBlocking {
        answer(LyricsSource.KUGOU, fresh, LyricsSource.VIBE, other)
        loadDownloaded()
        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.KUGOU } }
        waitForSaved { it.lines == fresh }

        withContext(mainDispatcher) { controller.selectLyricsProvider(LyricsSource.VIBE) }

        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.VIBE } }
        val copy = waitForSaved { it.lines == other }
        assertEquals(LyricsSource.VIBE, copy.source)
        assertEquals(LyricsOverrideFile.Kind.USER, copy.kind)
    }

    @Test
    fun `the listener's pick is fetched fresh from its provider and the lookup's pick does not replace it`() = runBlocking {
        LyricsOverrideStore.write(context, "video", other, LyricsSource.VIBE, LyricsOverrideFile.Kind.USER)
        answer(LyricsSource.KUGOU, fresh, LyricsSource.VIBE, otherNewer)

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.VIBE } }
        assertEquals(otherNewer.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
        val copy = waitForSaved { it.lines == otherNewer }
        assertEquals(LyricsOverrideFile.Kind.USER, copy.kind)
        assertEquals(LyricsSource.VIBE, copy.source)
    }

    @Test
    fun `every provider missing online falls back to the saved sheet`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.KUGOU)
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> null }

        loadDownloaded()

        withTimeout(10_000L) { controller.lyrics.first { it != null } }
        assertEquals(saved.map { it.text }, controller.lyrics.value!!.map { it.text })
        assertNull(controller.lyricsSource.value)
    }

    private fun key(lines: List<LyricLine>, audio: String): Int =
        31 * audio.hashCode() + lines.joinToString("\n") { it.text }.hashCode()

    private fun seedChecked(aiKey: Int) {
        openStore(
            """{"entries":{"video":{"syncCheckVersion":1,"aiTextKey":$aiKey,"aiEngine":"DeepgramWhisper",""" +
                """"ai":{"knots":[0,13950],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":74}}}}""",
        )
    }

    @Test
    fun `the check a saved sheet bought is forgotten once, before a provider sheet with its lines`() = runBlocking {
        seedChecked(key(saved, "video|file"))

        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_ANSWER,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
        // Forgotten for good: the next play keeps whatever the provider sheet's own check stores.
        assertEquals(
            KoSavedLyrics.SavedCheck.KEEP,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
    }

    @Test
    fun `an answer for the stream is not the saved sheet's and stays`() = runBlocking {
        seedChecked(key(saved, "video|stream"))

        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_VERSION,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
        assertEquals(
            KoSavedLyrics.SavedCheck.KEEP,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
    }

    @Test
    fun `online a downloaded song forgets its saved sheet's check before the lookup`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.KUGOU)
        seedChecked(key(saved, "video|file"))
        answer(LyricsSource.KUGOU, saved, LyricsSource.VIBE, other)

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.KUGOU } }
        assertEquals(
            KoSavedLyrics.SavedCheck.KEEP,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
    }
}
