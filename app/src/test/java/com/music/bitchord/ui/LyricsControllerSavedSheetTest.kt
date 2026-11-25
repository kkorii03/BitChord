package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsMismatch
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    /** [name]: another file for a store swapped in mid-play, so a save the old one still has pending cannot touch it. */
    private fun openStore(json: String, name: String = "lyrics_timing_v1.json") {
        File(directory, name).writeText(json)
        val storeClass = Class.forName("com.music.bitchord.playback.smart.LyricsTiming\$Store")
        val ctor = storeClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        storeField.set(LyricsTiming, ctor.newInstance(File(directory, name)))
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

    private suspend fun waitForSaved(
        id: String = "video",
        check: (LyricsOverrideFile.Stored) -> Boolean,
    ): LyricsOverrideFile.Stored =
        withTimeout(10_000L) {
            var stored = LyricsOverrideStore.read(context, id)
            while (stored == null || !check(stored)) {
                delay(50L)
                stored = LyricsOverrideStore.read(context, id)
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
     * 4WpwsUGuoGQ (11-25): the saved copy was the provider's own sheet read back from the file tag,
     * with an intro row first and centisecond times. It counted as another sheet, so the sync bought
     * for it was dropped on the next online play.
     */
    @Test
    fun `the saved sheet with an intro row keeps its check under the provider's lines`() = runBlocking {
        val fromTag = listOf(LyricLine(0L, "")) + saved
        val provider = saved.map { it.copy(timeMs = it.timeMs + 7L) }
        LyricsOverrideStore.write(context, "video", fromTag, LyricsSource.KUGOU)
        seedChecked(key(fromTag, "video|file"))
        answer(LyricsSource.KUGOU, provider, LyricsSource.VIBE, other)

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
        // Before: FORGET_VERSION or KEEP, the answer dropped with the saved form of the sheet.
        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_ANSWER,
            LyricsTiming.forgetSavedSheetCheck(context, "video", provider, isVideo = false),
        )
    }

    /** A per-line answer from before the fits is tied to the rows: it goes, as before. */
    @Test
    fun `an old per-line answer is not carried onto other rows`() = runBlocking {
        val fromTag = listOf(LyricLine(0L, "")) + saved
        openStore(
            """{"entries":{"video":{"syncCheckVersion":1,"aiTextKey":${key(fromTag, "video|file")},""" +
                """"aiStarts":[${fromTag.joinToString(",") { it.timeMs.toString() }}]}}}""",
        )

        assertEquals(false, LyricsTiming.carrySavedSheetCheck(context, "video", fromTag, saved, isVideo = false))
        assertEquals(
            KoSavedLyrics.SavedCheck.FORGET_ANSWER,
            LyricsTiming.forgetSavedSheetCheck(context, "video", fromTag, isVideo = false),
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

    /**
     * WDYM (11-18, DK_0jXPuIr0): the saved sheet's provider (lrc.red, ranked ahead) missed the MV and
     * a lower-ranked one (QQ, consensus unknown) answered with a bootleg's sheet. It was shown early,
     * then saved over the copy ("lyrics-fix: saved=lrc.red -> QQ Music reason=online-refresh").
     */
    @Test
    fun `a lower-ranked provider standing in for the saved sheet's missed provider does not replace it`() = runBlocking {
        LyricsOverrideStore.write(context, "video", saved, LyricsSource.KUGOU)
        val shown = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val watch = scope.launch { controller.lyrics.collect { lines -> lines?.let { shown.add(it.map { line -> line.text }) } } }
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            onResult?.invoke(LyricsSource.KUGOU, null, null)
            LyricsRepository.Result(LyricsSource.VIBE, fresh).also { onResult?.invoke(LyricsSource.VIBE, it, null) }
        }

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(300L)
        watch.cancel()
        // Before the fix: VIBE's sheet, credited to VIBE, and the copy rewritten to it.
        assertEquals(saved.map { it.text }, controller.lyrics.value!!.map { it.text })
        assertNull(controller.lyricsSource.value)
        assertTrue(shown.none { it == fresh.map { line -> line.text } })
        val copy = LyricsOverrideStore.read(context, "video")!!
        assertEquals(LyricsSource.KUGOU, copy.source)
        assertEquals(saved.map { it.text }, copy.lines.map { it.text })
    }

    /**
     * WDYM: the copy that rule came too late for (QQ's, written over the file's lrc.red sheet) gives
     * way online to the file's own sheet from the higher-ranked provider once every provider misses.
     */
    @Test
    fun `a lower-ranked automatic copy gives way to the file's own sheet when every provider misses`() = runBlocking {
        controller.fileLyrics = { _, _ -> EmbeddedLyrics.Embedded(fresh, LyricsSource.KUGOU, "video") }
        LyricsOverrideStore.write(context, "video", other, LyricsSource.VIBE)
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> null }

        loadDownloaded()

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        // Before the fix: the VIBE copy.
        assertEquals(fresh.map { it.text }, controller.lyrics.value!!.map { it.text })
        // Owner 11-18: the wrong copy is gone from the store, so an offline play shows the file's sheet too.
        withTimeout(10_000L) { while (LyricsOverrideStore.read(context, "video") != null) kotlinx.coroutines.delay(20) }
    }

    /**
     * Sixteen timed lines of four words, letters only: 64 heard words, enough for the words' judge.
     * Made-up tokens, no lyric text.
     */
    private fun lettered(tag: Char): List<LyricLine> =
        List(16) { i ->
            val line = 'a' + i
            LyricLine(5_000L + i * 4_000L, "$tag${line}a $tag${line}b $tag${line}c $tag${line}d")
        }

    /**
     * The same shape in digits only. No character in common with [lettered], so against its words not
     * one line opening is heard and the judge rejects it (as LyricsControllerSheetChoiceTest's
     * "901 902 …" sheet). Two letter sheets share letters, and the letter alignment places them.
     */
    private fun numbered(): List<LyricLine> =
        List(16) { i -> LyricLine(5_000L + i * 4_000L, (1..4).joinToString(" ") { k -> "${9_000 + i * 10 + k}" }) }

    /** Words already heard from this recording: [lines]' own, at their starts (as LyricsControllerSheetChoiceTest). */
    private fun seedHeard(lines: List<LyricLine>, videoId: String, storeName: String = "lyrics_timing_v1.json") {
        val words = lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word ->
                val at = line.timeMs + i * 500L
                """{"t":"$word","s":$at,"e":${at + 400L}}"""
            }
        }.joinToString(",")
        openStore("""{"entries":{"$videoId":{"heardCheckKey":1,"heardCheckWords":[$words]}}}""", storeName)
    }

    /**
     * LYR7-A (RG6 LYR6-A): online, a provider's sheet of another song was saved as the downloaded
     * song's copy before any words were heard. The words then rejected it, but the reload found the
     * saved copy and showed it again as the fallback, over the file's own right sheet.
     */
    @Test
    fun `a provider sheet the words reject is not kept, and the file's own sheet comes back`() = runBlocking {
        val id = "lyr7-rejected"
        val own = lettered('o')
        val wrong = numbered()
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

        // No words heard yet: the online refresh saves the provider's sheet as the copy, unjudged.
        waitForSaved(id) { stored -> stored.lines.map { it.text } == wrong.map { it.text } }
        // Then the recording's words land (own's) and the timing change asks the judge again.
        seedHeard(own, id, storeName = "lyrics_timing_heard.json")
        LyricsTiming.notifyStreamChoice(id)

        withTimeout(10_000L) { while (looks.get() < 2) delay(50L) }
        // Before LYR7 the reload put the rejected sheet back from its saved copy, and this timed out.
        withTimeout(10_000L) { controller.lyrics.first { shown -> shown?.map { it.text } == own.map { it.text } } }
        val copy = LyricsOverrideStore.read(context, id)
        assertTrue(copy == null || copy.lines.map { it.text } != wrong.map { it.text })
    }

    /**
     * LYR7 review: dropping a rejected sheet's copy waits on keepSheetLock and the file. A track
     * change landing in that wait was undone: the reload of the old request released the new
     * track's claim and put the old song's lookup (and sheet) on screen for the new one.
     */
    @Test
    fun `a rejection waiting to drop its copy does not reload the old song over the next one`() = runBlocking {
        val first = "lyr7-race-a"
        val next = "lyr7-race-b"
        val wrong = numbered()
        val nextSheet = lettered('n')
        seedHeard(lettered('h'), first)
        val firstLooks = AtomicInteger()
        controller.lyricsLookup = { videoId, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            val answer = if (videoId == first) {
                firstLooks.incrementAndGet()
                LyricsRepository.Result(LyricsSource.KUGOU, wrong)
            } else {
                LyricsRepository.Result(LyricsSource.KUGOU, nextSheet)
            }
            onResult?.invoke(LyricsSource.KUGOU, answer, null)
            answer
        }
        val lock = LyricsController::class.java.getDeclaredField("keepSheetLock")
            .apply { isAccessible = true }
            .get(controller) as Mutex
        lock.lock()
        val looked = try {
            withContext(mainDispatcher) { controller.loadLyrics(first, "Song", "Someone", 200_000L) }
            // The words reject the first song's sheet; the drop of its copy then waits on the lock held here.
            withTimeout(10_000L) { while (!LyricsMismatch.isRejected(first, LyricsSource.KUGOU, wrong)) delay(20L) }
            // Read on the main thread, as the skip lands: a reload already run (no wait) is counted.
            val count = withContext(mainDispatcher) {
                firstLooks.get().also { controller.loadLyrics(next, "Next", "Someone", 200_000L) }
            }
            withTimeout(10_000L) { controller.lyrics.first { shown -> shown?.map { it.text } == nextSheet.map { it.text } } }
            count
        } finally {
            lock.unlock()
        }

        // Before the fix the first song's reload ran here: its lookup again, its sheet over the next song's.
        withTimeoutOrNull(2_000L) { while (firstLooks.get() <= looked) delay(20L) }
        assertEquals(looked, firstLooks.get())
        assertEquals(nextSheet.map { it.text }, controller.lyrics.value?.map { it.text })
    }
}
