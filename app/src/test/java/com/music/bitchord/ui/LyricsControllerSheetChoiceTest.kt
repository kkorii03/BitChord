package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A sheet the words heard do not fit gives way to one in hand that they do,
 * at once and without a lookup or an empty screen (rlYOsUfpi3M, w58uXpyHWes).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerSheetChoiceTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var controller: LyricsController
    private lateinit var directory: File
    private lateinit var context: Context

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val savedSources = AppSettings.lyricsSources.value
    private val savedOrder = AppSettings.lyricsSourceOrder.value
    private val savedSynced = AppSettings.syncedLyrics.value

    private val fitting = sheet(4_000L, "amber birch cedar", "dune ember frost", "glade hazel ivory", "juniper kelp lotus")

    /** Another song: other words on another pace, which is what lets the two be told apart. */
    private val other = sheet(2_500L, "maple nectar olive", "pebble quartz reed", "sage thistle umber", "violet willow xenon")

    private fun sheet(everyMs: Long, vararg texts: String): List<LyricLine> =
        List(16) { i -> LyricLine(5_000L + i * everyMs, texts[i % texts.size] + " " + (i + 1)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
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
        AppSettings.lyricsSources.value = savedSources
        AppSettings.lyricsSourceOrder.value = savedOrder
        AppSettings.syncedLyrics.value = savedSynced
        AppSettings.syncSafetyDownloadRecheck.value = false
        StreamChoice.forget("video")
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    /** Words already heard from this recording, for a sheet they did not fit. */
    private fun seedHeard(lines: List<LyricLine>) {
        val words = lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word ->
                val at = line.timeMs + i * 500L
                """{"t":"$word","s":$at,"e":${at + 400L}}"""
            }
        }.joinToString(",")
        openStore("""{"entries":{"video":{"heardCheckKey":1,"heardCheckWords":[$words]}}}""")
    }

    private fun openStore(json: String) {
        File(directory, "lyrics_timing_v1.json").writeText(json)
        val storeClass = Class.forName("com.music.bitchord.playback.smart.LyricsTiming\$Store")
        val ctor = storeClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        storeField.set(LyricsTiming, ctor.newInstance(File(directory, "lyrics_timing_v1.json")))
    }

    private fun load(winner: LyricsSource, lines: List<LyricLine>, loser: LyricsSource, loserLines: List<LyricLine>) {
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            val winnerResult = LyricsRepository.Result(winner, lines)
            onResult?.invoke(loser, LyricsRepository.Result(loser, loserLines), null)
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
    private val keptWrong = List(16) { i -> LyricLine(5_000L + i * 4_000L, "가나다 라마바 사아자 ${i + 1}번") }

    /**
     * A sheet kept on the device came through no lookup, so nothing judged it: a wrong one
     * stayed to the last line of every play. Asked for again, and no longer kept.
     */
    @Test
    fun `a kept sheet the words prove wrong gives way to a lookup`() = runBlocking {
        seedHeard(fitting)
        LyricsOverrideStore.write(context, "video", keptWrong, LyricsSource.KUGOU)

        load(LyricsSource.VIBE, fitting, LyricsSource.KUGOU, fitting)

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
}
