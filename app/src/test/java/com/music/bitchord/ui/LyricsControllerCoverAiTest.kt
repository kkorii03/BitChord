package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A cover is an upload: its AI answer is stored under the video key (the
 * lyrics text alone, no audio-source suffix). The screen used to tell
 * [LyricsController] nothing about the video flag, so it asked for the stream
 * key and never saw the answer the next-song bundle had already paid for
 * (11-02, v59g4HRNIkc).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerCoverAiTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var controller: LyricsController
    private lateinit var directory: File

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val savedSources = AppSettings.lyricsSources.value
    private val savedOrder = AppSettings.lyricsSourceOrder.value
    private val savedSynced = AppSettings.syncedLyrics.value

    private val lines = listOf(
        LyricLine(1_000L, "one"),
        LyricLine(2_000L, "two"),
        LyricLine(3_000L, "three"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        AppSettings.syncedLyrics.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.KUGOU)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.KUGOU)
        directory = Files.createTempDirectory("bitchord-cover-ai").toFile()
        storeField.set(LyricsTiming, null)
        // A bare Application() has no files dir in the unit-test stub. The
        // timing job outlives this test, so teardown's store reset would make
        // it throw an uncaught NPE into whichever test runs next.
        controller = LyricsController(
            TestApplication(FakeContext.create(FakePreferences(), directory)),
            scope,
        )
    }

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        Dispatchers.resetMain()
        AppSettings.lyricsSources.value = savedSources
        AppSettings.lyricsSourceOrder.value = savedOrder
        AppSettings.syncedLyrics.value = savedSynced
        StreamChoice.forget("vSubTiming")
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
    }

    private fun seedAnswer(wordsKey: Int) {
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"v59g4HRNIkc":{
              "aiTextKey":$wordsKey,
              "ai":{"knots":[0,0],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":97},
              "aiEngine":"Gemini"
            }}}
            """.trimIndent(),
        )
        openStore()
    }

    /** Points the timing store at this test's file, after it has been written. */
    private fun openStore() {
        val storeClass = Class.forName("com.music.bitchord.playback.smart.LyricsTiming\$Store")
        val ctor = storeClass.declaredConstructors.first { it.parameterCount == 1 }
        ctor.isAccessible = true
        storeField.set(LyricsTiming, ctor.newInstance(File(directory, "lyrics_timing_v1.json")))
    }

    @Test
    fun `a substitute stream still gets the answer measured on its own clock`() = runBlocking {
        val videoId = "vSubTiming"
        // A raw string does not process \u escapes: build the clock tag as a
        // real string, as the store writes it.
        val copyClock = "alt\u0001src\u0001track"
        StreamChoice.remember(
            videoId,
            SourceStream(
                url = "https://example.test/copy",
                sourceConfigId = "src",
                trackId = "track",
            ),
            substituted = true,
        )
        File(directory, "lyrics_timing_v1.json").writeText(
            """
            {"entries":{"$videoId":{
              "autoVersion":4,
              "auto":{"knots":[0,1000],"headRate":1.0,"tailRate":1.0,"rate":1.0,"match":90},
              "autoClock":"$copyClock"
            }}}
            """.trimIndent(),
        )
        openStore()
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            LyricsRepository.Result(source = LyricsSource.KUGOU, lines = lines)
        }

        controller.loadLyricsFor(
            Song(
                videoId = videoId,
                title = "Cover",
                artist = "Someone",
                thumbnailUrl = null,
                isVideo = false,
            ),
            durationMs = 200_000L,
        )

        // The stored answer was measured on this very copy, so its +1 s belongs
        // on screen. Refusing every substitute up front dropped it and showed
        // every line 1 s early.
        val timing = withTimeout(5_000L) { controller.lyricsTiming.first { it.shiftMs != null } }
        assertEquals(1_000L, timing.shiftMs ?: 0L)
    }

    @Test
    fun `a cover answer kept for the video applies to the screen`() = runBlocking {
        seedAnswer(lines.joinToString("\n") { it.text }.hashCode())
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            LyricsRepository.Result(source = LyricsSource.KUGOU, lines = lines)
        }

        controller.loadLyricsFor(
            Song(
                videoId = "v59g4HRNIkc",
                title = "Ponyo",
                artist = "Someone",
                thumbnailUrl = null,
                isVideo = true,
            ),
            durationMs = 215_000L,
        )

        val timing = withTimeout(5_000L) { controller.lyricsTiming.first { it.byAi } }
        assertTrue(timing.byAi)
    }

    @Test
    fun `the player screen asks for lyrics with the whole row, so MVs and covers keep their video flag`() {
        // The 11-02 bug was at this call site: it passed title/artist only and
        // the controller's isVideo defaulted to false for every MV and cover.
        val main = File("src/main/java/com/music/bitchord/MainActivity.kt").readText()
        assertTrue(
            "MainActivity must hand the Song to loadLyrics (see LyricsController.loadLyricsFor)",
            Regex("""viewModel\.loadLyrics\(\s*it,""").containsMatchIn(main),
        )
        val model = File("src/main/java/com/music/bitchord/ui/MainViewModel.kt").readText()
        assertTrue(model.contains("lyricsController.loadLyricsFor(song, durationMs)"))
    }

    @Test
    fun `an answer kept for other lyrics says why it was not used`() = runBlocking {
        val otherLines = listOf(LyricLine(1_000L, "uno"), LyricLine(2_000L, "dos"))
        val otherKey = otherLines.joinToString("\n") { it.text }.hashCode()
        seedAnswer(otherKey)
        val context = FakeContext.create(FakePreferences(), directory)

        assertEquals(
            null,
            LyricsTiming.timing(
                context = context,
                videoId = "v59g4HRNIkc",
                lines = lines,
                baseAllowed = true,
                isVideo = true,
            ),
        )

        assertTrue(
            TrackLog.exportText().contains("ai answer for other lyrics: $otherKey"),
        )
    }
}

private class TestApplication(private val base: Context) : Application() {
    override fun getApplicationContext(): Context = base
    override fun getFilesDir(): File = base.filesDir
}
