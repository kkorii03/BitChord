package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [KO] matchpct (11-18 WDYM DK_0jXPuIr0): a chroma measurement matched the video to the release
 * at 100%, and the screen read that figure as "these lyrics fit": the QQ bootleg sheet on screen
 * was never judged by the words heard, and the sheet they did fit was never put up. A chroma or HQ
 * match times the recording; only the words' own fit of a sheet ([LyricsTiming.measuredFitFor])
 * may keep it from the word verdict. Lines are made-up identifiers, no lyric text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChromaMatchSheetChoiceTest {

    @get:org.junit.Rule
    val koTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

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

    private val id = "chroma-matched"

    private val fitting = sheet(4_000L, "fa01 fb02 fc03", "fd04 fe05 ff06", "fg07 fh08 fi09", "fj10 fk11 fl12")

    /** Another song's sheet: other words on another pace. */
    private val other = sheet(2_500L, "ma01 mb02 mc03", "md04 me05 mf06", "mg07 mh08 mi09", "mj10 mk11 ml12")

    private fun sheet(everyMs: Long, vararg texts: String): List<LyricLine> =
        List(16) { i -> LyricLine(5_000L + i * everyMs, texts[i % texts.size] + " " + (i + 1)) }

    /** A chroma measurement of this recording against the release: 100%, +500 ms. Not keyed to any sheet. */
    private val chroma = """"autoVersion":${LyricsTiming.AUTO_VERSION},"autoComplete":true,""" +
        """"auto":{"knots":[0,500],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":100}"""

    @Before
    fun setUp() {
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lyrics-test-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        scope = CoroutineScope(job + mainDispatcher)
        AppSettings.syncedLyrics.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.KUGOU, LyricsSource.VIBE)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.KUGOU, LyricsSource.VIBE)
        directory = Files.createTempDirectory("bitchord-chroma-fit").toFile()
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
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun openStore(entry: String) {
        val file = File(directory, "lyrics_timing_v1.json")
        file.writeText("""{"entries":{"$id":{$entry}}}""")
        val storeClass = Class.forName("com.music.bitchord.playback.smart.LyricsTiming\$Store")
        val ctor = storeClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        storeField.set(LyricsTiming, ctor.newInstance(file))
    }

    /** The words a listen of this recording heard: [lines]' own, where they open. */
    private fun heard(lines: List<LyricLine>): String {
        val words = lines.flatMap { line ->
            line.text.split(' ').mapIndexed { i, word ->
                val at = line.timeMs + i * 500L
                """{"t":"$word","s":$at,"e":${at + 400L}}"""
            }
        }.joinToString(",")
        return """"heardCheckKey":1,"heardCheckWords":[$words]"""
    }

    /**
     * The wrong sheet is on screen and timed by the 100% chroma match; then the words land and the
     * sheet they fit arrives as a candidate. Old code: knownFit=100 skipped both the sheet choice and
     * the mismatch judge, and the wrong sheet stayed.
     */
    @Test
    fun `a chroma match does not keep a sheet the words do not fit`() = runBlocking {
        openStore(chroma)
        load(wrongFirst = other)
        // The chroma timing is on screen, as on the phone (lyrics-sync ... match=100%).
        withTimeout(10_000L) { controller.lyricsTiming.first { it.matchPercent == 100 && !it.byAi } }
        openStore("$chroma,${heard(fitting)}")
        go.complete(Unit)

        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.VIBE } }
        assertEquals(fitting.map { it.text }, controller.lyrics.first { it != null }!!.map { it.text })
    }

    private val go = CompletableDeferred<Unit>()

    /**
     * [wrongFirst] answers first from the top source and goes on screen; the sheet the words fit
     * is the lookup's own late answer once [go] completes: a candidate the words then decide.
     */
    private suspend fun load(wrongFirst: List<LyricLine>) {
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, onResult, _, _, _, _, _, _ ->
            onResult?.invoke(LyricsSource.KUGOU, LyricsRepository.Result(LyricsSource.KUGOU, wrongFirst), null)
            go.await()
            LyricsRepository.Result(LyricsSource.VIBE, fitting)
        }
        controller.loadLyrics(id, "Song", "Someone", 200_000L)
        withTimeout(10_000L) { controller.lyricsSource.first { it == LyricsSource.KUGOU } }
    }

    /**
     * The guard the chroma figure was standing in for still holds (tjyFqEaWJqA): a sheet the words
     * themselves fitted at 99% is not thrown away by a listen that does not fit it.
     */
    @Test
    fun `a sheet the words fitted stays whatever a later listen heard`() = runBlocking {
        val key = 31 * LyricsTiming.audioSourceKey(id, localFile = false).hashCode() +
            other.joinToString("\n") { it.text }.hashCode()
        val wordsFit = """"aiTextKey":$key,"aiEngine":"${LyricsTiming.ENGINE_DEEPGRAM_WHISPER}",""" +
            """"ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":99}"""
        openStore("$wordsFit,${heard(fitting)}")
        load(wrongFirst = other)
        go.complete(Unit)

        withTimeout(10_000L) { controller.lyricsChecked.first { it } }
        delay(500L)
        assertEquals(LyricsSource.KUGOU, controller.lyricsSource.value)
        assertEquals(other.map { it.text }, controller.lyrics.value!!.map { it.text })
    }
}
