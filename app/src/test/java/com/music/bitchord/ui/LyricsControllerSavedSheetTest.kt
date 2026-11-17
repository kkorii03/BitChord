package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.EmbeddedLyrics
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
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertTrue
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

    private suspend fun loadDownloaded(id: String = "video") = withContext(mainDispatcher) {
        controller.loadLyrics(id, "Song", "Someone", 200_000L, localUri = file)
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

    /** LYR7-C: a provider sheet other than the saved one makes the saved sheet's check stale (guard). */
    @Test
    fun `online another provider sheet settling forgets the saved sheet's check`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.KUGOU)
        seedChecked(key(saved, "video|file"))
        answer(LyricsSource.KUGOU, fresh, LyricsSource.VIBE, other)

        loadDownloaded()

        // Set once the provider sheet is published, after the check was dropped.
        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
        assertEquals(
            KoSavedLyrics.SavedCheck.KEEP,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
    }

    /**
     * LYR7-C (RG6 LYR6-C): the check used to be dropped before the lookup, so a song every provider
     * misses lost its saved sheet's answer on every online play and bought a new paid re-check.
     */
    @Test
    fun `every provider missing online keeps the check the saved sheet bought`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.KUGOU)
        seedChecked(key(saved, "video|file"))
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> null }

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        assertEquals(saved.map { it.text }, controller.lyrics.value!!.map { it.text })
        // Before LYR7: KEEP, the answer already gone.
        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_ANSWER,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
    }

    /**
     * LYR7-C: the provider answering the very lines the saved sheet holds (start and words) is the
     * sheet that check measured on this file; its answer still holds and is not bought again.
     */
    @Test
    fun `the saved sheet coming back from a provider keeps its check`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.KUGOU)
        seedChecked(key(saved, "video|file"))
        answer(LyricsSource.KUGOU, saved, LyricsSource.VIBE, other)

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
        // Before LYR7: KEEP, dropped before the lookup whatever it answered.
        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_ANSWER,
            LyricsTiming.forgetSavedSheetCheck(context, "video", saved, isVideo = false),
        )
    }

    /**
     * LYR7-B (RG6 LYR6-B): with the listener's own pick saved, the picked provider missing let the
     * lookup's best (another provider) replace it, forced, and kept the words from judging it.
     */
    @Test
    fun `the listener's picked provider missing online keeps their saved pick on screen`() = runBlocking {
        LyricsOverrideStore.write(context, "video", other, LyricsSource.VIBE, LyricsOverrideFile.Kind.USER)
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            LyricsRepository.Result(LyricsSource.KUGOU, fresh).also { onResult?.invoke(LyricsSource.KUGOU, it, null) }
        }

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(300L)
        // Before LYR7: KuGou's sheet, credited to KuGou.
        assertEquals(other.map { it.text }, controller.lyrics.value!!.map { it.text })
        assertNull(controller.lyricsSource.value)
        val copy = LyricsOverrideStore.read(context, "video")!!
        assertEquals(LyricsOverrideFile.Kind.USER, copy.kind)
        assertEquals(LyricsSource.VIBE, copy.source)
        assertEquals(other.map { it.text }, copy.lines.map { it.text })
    }

    /** Sixteen lines of four words: enough heard words and lines for the words' judge. Made-up tokens. */
    private fun wordy(tag: String): List<LyricLine> =
        List(16) { i -> LyricLine(5_000L + i * 4_000L, "${tag}a$i ${tag}b$i ${tag}c$i ${tag}d$i") }

    /** Words already heard from this recording: [lines]' own, at their starts (as LyricsControllerSheetChoiceTest). */
    private fun seedHeard(lines: List<LyricLine>, videoId: String) {
        val words = lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word ->
                val at = line.timeMs + i * 500L
                """{"t":"$word","s":$at,"e":${at + 400L}}"""
            }
        }.joinToString(",")
        openStore("""{"entries":{"$videoId":{"heardCheckKey":1,"heardCheckWords":[$words]}}}""")
    }

    /**
     * LYR7-A (RG6 LYR6-A): online, a provider's sheet of another song was saved as the downloaded
     * song's copy before any words were heard. The words then rejected it, but the reload found the
     * saved copy and showed it again as the fallback, over the file's own right sheet.
     */
    @Test
    fun `a provider sheet the words reject is not kept, and the file's own sheet comes back`() = runBlocking {
        val id = "lyr7-rejected"
        val own = wordy("ow")
        val wrong = wordy("wr")
        seedHeard(own, id)
        controller.fileLyrics = { _, _ -> EmbeddedLyrics.Embedded(own, LyricsSource.VIBE, id) }
        val looks = AtomicInteger()
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            if (looks.incrementAndGet() == 1) {
                LyricsRepository.Result(LyricsSource.KUGOU, wrong).also { onResult?.invoke(LyricsSource.KUGOU, it, null) }
            } else {
                null
            }
        }

        loadDownloaded(id)

        withTimeout(10_000L) { while (looks.get() < 2) delay(50L) }
        // Before LYR7 the reload put the rejected sheet back from its saved copy, and this timed out.
        withTimeout(10_000L) { controller.lyrics.first { shown -> shown?.map { it.text } == own.map { it.text } } }
        val copy = LyricsOverrideStore.read(context, id)
        assertTrue(copy == null || copy.lines.map { it.text } != wrong.map { it.text })
    }
}
