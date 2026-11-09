package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsMismatch
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
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.music.bitchord.ui.player.LyricsTimingUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A sheet the words heard do not fit gives way to one in hand that they do,
 * at once and without a lookup or an empty screen (rlYOsUfpi3M, w58uXpyHWes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerSheetChoiceTest {

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

    private val fitting = sheet(4_000L, "fa01 fb02 fc03", "fd04 fe05 ff06", "fg07 fh08 fi09", "fj10 fk11 fl12")

    /** Another song: other words on another pace, which is what lets the two be told apart. */
    private val other = sheet(2_500L, "ma01 mb02 mc03", "md04 me05 mf06", "mg07 mh08 mi09", "mj10 mk11 ml12")

    private fun sheet(everyMs: Long, vararg texts: String): List<LyricLine> =
        List(16) { i -> LyricLine(5_000L + i * everyMs, texts[i % texts.size] + " " + (i + 1)) }

    @Before
    fun setUp() {
        // The app keeps the lookup and every display write on Main, a thread of its
        // own, and off-loads measurement/alignment to Dispatchers.Default. Pointing
        // Main at that same shared pool let a busy pool leave the first sheet
        // unscheduled past a test's wait (11-07: `a same-source late sheet ...`
        // timed out, 42355390925 / 42369413521). So the class gets one thread.
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lyrics-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        scope = CoroutineScope(job + mainDispatcher)
        AppSettings.syncedLyrics.value = true
        AppSettings.syncSafetyDownloadRecheck.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.KUGOU, LyricsSource.VIBE)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.KUGOU, LyricsSource.VIBE)
        directory = Files.createTempDirectory("bitchord-sheet-choice").toFile()
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
        AppSettings.syncSafetyDownloadRecheck.value = false
        StreamChoice.forget("video")
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    /** Words already heard from this recording, for a sheet they did not fit. */
    private fun seedHeard(lines: List<LyricLine>, videoId: String = "video") {
        val words = lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word ->
                val at = line.timeMs + i * 500L
                """{"t":"$word","s":$at,"e":${at + 400L}}"""
            }
        }.joinToString(",")
        openStore("""{"entries":{"$videoId":{"heardCheckKey":1,"heardCheckWords":[$words]}}}""")
    }

    private fun openStore(json: String) {
        File(directory, "lyrics_timing_v1.json").writeText(json)
        val storeClass = Class.forName("com.music.bitchord.playback.smart.LyricsTiming\$Store")
        val ctor = storeClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        storeField.set(LyricsTiming, ctor.newInstance(File(directory, "lyrics_timing_v1.json")))
    }

    private fun judgedFor(): String? =
        LyricsController::class.java.getDeclaredField("sheetJudgedFor").apply { isAccessible = true }.get(controller) as String?

    @Suppress("UNCHECKED_CAST")
    private fun timingFlow(): MutableStateFlow<LyricsTimingUi> =
        LyricsController::class.java.getDeclaredField("_lyricsTiming").apply { isAccessible = true }.get(controller) as MutableStateFlow<LyricsTimingUi>

    /**
     * [loserFirst] holds the winner back until the lower-priority answer is on
     * screen: the order a slow lookup reaches the screen in by chance, and the
     * one that used to leave the source at the loser's (11-07 flake).
     */
    private fun load(
        winner: LyricsSource,
        lines: List<LyricLine>,
        loser: LyricsSource,
        loserLines: List<LyricLine>,
        loserFirst: Boolean = false,
    ) {
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            val winnerResult = LyricsRepository.Result(winner, lines)
            onResult?.invoke(loser, LyricsRepository.Result(loser, loserLines), null)
            if (loserFirst) withTimeout(10_000L) { controller.lyricsSource.first { it == loser } }
            onResult?.invoke(winner, winnerResult, null)
            winnerResult
        }
        controller.loadLyrics("video", "Song", "Someone", 200_000L)
    }

    @Test
    fun `the race's pick gives way to the sheet the words fit`() = runBlocking {
        seedHeard(fitting)

        load(LyricsSource.KUGOU, other, LyricsSource.VIBE, fitting)

        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.VIBE } }
        assertEquals(fitting.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
    }

    @Test
    fun `the sheet they chose is what the song opens with next time`() = runBlocking {
        seedHeard(fitting)

        load(LyricsSource.KUGOU, other, LyricsSource.VIBE, fitting)

        withTimeout(10_000L) {
            while (LyricsOverrideStore.read(context, "video") == null) delay(50L)
        }
        assertEquals(fitting.map { it.text }, LyricsOverrideStore.read(context, "video")!!.lines.map { it.text })
    }

    /** Letters the words heard have none of: a kept sheet of another song. Made up. */
    private val keptWrong = List(16) { i -> LyricLine(5_000L + i * 4_000L, "vux wux xux yux") }

    /**
     * A sheet kept on the device came through no lookup, so nothing judged it: a wrong one
     * stayed to the last line of every play. Asked for again, and no longer kept.
     */
    @Test
    fun `a kept sheet the words prove wrong gives way to a lookup`() = runBlocking {
        seedHeard(fitting)
        LyricsOverrideStore.write(context, "video", keptWrong, LyricsSource.KUGOU)

        load(LyricsSource.VIBE, fitting, LyricsSource.KUGOU, fitting, loserFirst = true)

        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.VIBE } }
        assertEquals(fitting.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
        assertEquals(null, LyricsOverrideStore.read(context, "video"))
    }

    @Test
    fun `the listener's own sheet stays whatever was heard`() = runBlocking {
        seedHeard(fitting)
        LyricsOverrideStore.write(
            context,
            "video",
            keptWrong,
            LyricsSource.KUGOU,
            com.music.bitchord.data.lyrics.LyricsOverrideFile.Kind.USER,
        )

        load(LyricsSource.VIBE, fitting, LyricsSource.KUGOU, fitting)

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(500L)
        assertEquals(keptWrong.map { it.text }, controller.lyrics.value!!.map { it.text })
    }

    /** 11-06 w58uXpyHWes: its corrected sheet gave way to the file's, uncorrected, for 71 s. */
    @Test
    fun `a download taking over keeps the corrected sheet with no frame without it`() = runBlocking {
        StreamChoice.remember(
            "video",
            SourceStream(url = "https://example.test/copy", sourceConfigId = "src", trackId = "track"),
            substituted = true,
        )
        val key = 31 * "video|stream".hashCode() + fitting.joinToString("\n") { it.text }.hashCode()
        val clock = "alt\u0001src\u0001track"
        openStore(
            """{"entries":{"video":{"aiTextKey":$key,"aiEngine":"Gemini","aiClock":"$clock",""" +
                """"ai":{"knots":[0,500],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90}}}}""",
        )
        load(LyricsSource.KUGOU, fitting, LyricsSource.VIBE, fitting)
        withTimeout(10_000L) { controller.lyricsTiming.first { it.shiftMs == 500L } }
        val shown = java.util.concurrent.CopyOnWriteArrayList<List<LyricLine>?>()
        val watching = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { controller.lyrics.collect { shown += it } }

        controller.loadLyrics("video", "Song", "Someone", 200_000L, localUri = "file:///music/song.flac")

        withTimeout(10_000L) { controller.lyricsTiming.first { it.shiftMs == 500L } }
        delay(300L)
        watching.cancel()
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
        assertEquals(listOf(fitting[0].timeMs + 500L), shown.map { it!!.first().timeMs }.distinct())
    }

    @Test
    fun `a sheet the words fit is left on screen`() = runBlocking {
        seedHeard(fitting)

        load(LyricsSource.KUGOU, fitting, LyricsSource.VIBE, other)

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(500L)
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
    }

    /**
     * QYh6mYIJG2Y auto89: the first sheet fit 82% of lines in 0.3 s, the late
     * provider's 2%, and it replaced the first 5.1 s later. With no words heard
     * yet there is nothing to prove the late sheet better, so the sheet already
     * on screen stays; the words heard decide later (SheetChoice).
     */
    @Test
    fun `a late provider does not replace the sheet already on screen`() = runBlocking {
        load(LyricsSource.KUGOU, other, LyricsSource.VIBE, fitting, loserFirst = true)

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(500L)
        assertEquals(LyricsSource.VIBE, controller.lyricsSource.value)
        assertEquals(fitting.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
    }

    /**
     * qsqvJpOgrWw auto93: the same provider's first sheet fit 96% of lines in
     * 0.3 s (24/46 matched), its second 26% (23/46), and the second replaced the
     * first 0.2 s later. A differing sheet from the same source is still a late
     * answer: with no words heard it must not replace the sheet on screen.
     */
    @Test
    fun `a same-source late sheet does not replace the sheet already on screen`() = runBlocking {
        load(LyricsSource.KUGOU, other, LyricsSource.KUGOU, fitting, loserFirst = true)

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(500L)
        assertEquals(fitting.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
    }

    /**
     * 11-10 n4uG9ym6kKA: the words arrived, the judgement read the sheet as untimed, and while
     * it summed the fits the sheet's own passing timing landed (75%). The judgement replaced it
     * anyway with a sheet that timed worse. A measure that changed mid-judgement is judged anew.
     */
    @Test
    fun `a timing that lands while the sheets are judged keeps the sheet it timed`() = runBlocking {
        val go = CompletableDeferred<Unit>()
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            onResult?.invoke(LyricsSource.KUGOU, LyricsRepository.Result(LyricsSource.KUGOU, other), null)
            go.await()
            LyricsRepository.Result(LyricsSource.VIBE, fitting)
        }
        controller.loadLyrics("video", "Song", "Someone", 200_000L)
        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.KUGOU } }
        withTimeout(10_000L) { while (judgedFor() == null) delay(50L) }
        delay(300L)
        seedHeard(fitting)
        val heardSize = fitting.sumOf { it.text.split(' ').size }
        val hold = CountDownLatch(1)
        val busy = (1..64).map { launch(Dispatchers.Default) { hold.await() } }
        try {
            go.complete(Unit)
            withTimeout(10_000L) { while (judgedFor()?.contains("|$heardSize|") != true) delay(20L) }
            timingFlow().update { it.copy(matchPercent = 90) }
        } finally {
            hold.countDown()
            busy.forEach { it.cancel() }
            for (task in busy) task.join()
        }
        delay(500L)
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
        assertEquals(other.map { it.text }, controller.lyrics.value!!.map { it.text })
    }

    /**
     * The first sheet's write goes to the screen while the shared pool the app's
     * measurement/alignment work runs on is fully busy: on 11-07 it did not,
     * because this class had pointed Dispatchers.Main at that same pool, and the
     * 10 s wait in [load] missed (42355390925, 42369413521).
     */
    @Test
    fun `the first sheet reaches the screen while the background pool is busy`() = runBlocking {
        // One task per pool thread blocks in await(): Dispatchers.Default never
        // grows past its parallelism, so it is busy until the latch opens.
        val hold = CountDownLatch(1)
        val busy = (1..64).map { launch(Dispatchers.Default) { hold.await() } }
        try {
            load(LyricsSource.KUGOU, other, LyricsSource.KUGOU, fitting, loserFirst = true)

            withTimeout(10_000L) { controller.lyricsChecked.first { it } }
            assertEquals(fitting.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
        } finally {
            hold.countDown()
            busy.forEach { it.cancel() }
            for (task in busy) task.join()
        }
    }

    /**
     * 11-08 KChyOsjqmfM: the screen threw the wrong sheet away but the lookup
     * fetched it back, because only the lookup's own judge remembered a
     * rejection. A remembered one makes the next lookup skip it.
     */
    @Test
    fun `a rejected sheet is remembered so the reload cannot show it again`() = runBlocking {
        val id = "wrong-screen"
        // Heard and shown sheets are disjoint identifier sets: no lyric text.
        val heardSheet = List(16) { LyricLine(5_000L + it * 4_000L, "ha hb hc hd") }
        val wrongSheet = List(16) { i -> LyricLine(5_000L + i * 4_000L, "901 902 903 ${900 + i}") }
        AppSettings.lyricsSources.value = setOf(LyricsSource.KUGOU)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.KUGOU)
        seedHeard(heardSheet, id)
        var looks = 0
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            looks += 1
            val wrong = LyricsRepository.Result(LyricsSource.KUGOU, wrongSheet)
            onResult?.invoke(LyricsSource.KUGOU, wrong, null)
            wrong
        }

        controller.loadLyrics(id, "Song", "Someone", 200_000L)

        withTimeout(10_000L) { while (looks < 2) delay(50L) }
        assertTrue(LyricsMismatch.isRejected(id, LyricsSource.KUGOU, wrongSheet))
    }
}
