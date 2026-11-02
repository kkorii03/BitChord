package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.playback.smart.SyncTestMode
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
 * Sync-test mode must cover every song the phone plays, not only ones whose
 * timing pass or song check ran: an untimed sheet still needs its shown lines
 * in the log, and a song with no downloaded file still needs its WAV.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsControllerSyncDumpTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var controller: LyricsController
    private lateinit var directory: File
    private lateinit var external: File

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val savedSources = AppSettings.lyricsSources.value
    private val savedOrder = AppSettings.lyricsSourceOrder.value
    private val savedSynced = AppSettings.syncedLyrics.value
    private val savedEcho = TrackLog.echo
    private val savedDecode = SyncTestMode.decodeHead

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        AppSettings.syncedLyrics.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.LRCLIB)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.LRCLIB)
        directory = Files.createTempDirectory("bitchord-sync-dump").toFile()
        external = Files.createTempDirectory("bitchord-sync-dump-ext").toFile()
        File(external, "sync-test.flag").writeText("")
        storeField.set(LyricsTiming, null)
        controller = LyricsController(
            TestApplication(FakeContext.create(FakePreferences(), directory), external),
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
        TrackLog.echo = savedEcho
        SyncTestMode.decodeHead = savedDecode
        storeField.set(LyricsTiming, null)
        directory.deleteRecursively()
        external.deleteRecursively()
    }

    private fun captureLog(): MutableList<String> {
        val lines = mutableListOf<String>()
        TrackLog.echo = { _, _, message, _ -> synchronized(lines) { lines += message } }
        return lines
    }

    @Test
    fun `an untimed sheet still logs the lines the screen shows`() = runBlocking {
        val id = "untimed0001"
        val lines = captureLog()
        SyncTestMode.decodeHead = { _, _ -> null }
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            LyricsRepository.Result(
                source = LyricsSource.LRCLIB,
                lines = listOf(LyricLine(0L, "one"), LyricLine(0L, "two"), LyricLine(0L, "three")),
            )
        }

        controller.loadLyrics(id, "Song", "Artist", durationMs = 200_000L)
        withTimeout(5_000L) { controller.lyricsChecked.first { it } }
        val dump = withTimeout(5_000L) {
            var found: String? = null
            while (found == null) {
                found = synchronized(lines) {
                    lines.firstOrNull { it.contains("sync-dump: begin $id ") }
                }
                if (found == null) kotlinx.coroutines.delay(10)
            }
            checkNotNull(found)
        }

        assertTrue("expected an untimed sync-dump, got $lines", dump.contains("base=none"))
    }

    @Test
    fun `a streamed song keeps its wav even without a local file`() = runBlocking {
        val id = "keptwav001"
        val decoded = mutableListOf<String?>()
        SyncTestMode.decodeHead = { _, song ->
            decoded += song.localUri
            byteArrayOf(1, 2, 3, 4)
        }
        controller.lyricsLookup = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
            LyricsRepository.Result(
                source = LyricsSource.LRCLIB,
                lines = listOf(LyricLine(1_000L, "one"), LyricLine(2_000L, "two"), LyricLine(3_000L, "three")),
            )
        }

        controller.loadLyrics(id, "Song", "Artist", durationMs = 200_000L)
        withTimeout(5_000L) { controller.lyricsChecked.first { it } }
        val wav = File(File(external, "synctest"), "$id.wav")
        withTimeout(5_000L) {
            while (!wav.isFile) kotlinx.coroutines.delay(10)
        }

        assertEquals(listOf(null), decoded)
        assertTrue(wav.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    private class TestApplication(
        private val base: Context,
        private val external: File,
    ) : Application() {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = base.filesDir
        override fun getSharedPreferences(name: String?, mode: Int) = base.getSharedPreferences(name, mode)
        override fun getExternalFilesDir(type: String?): File = external
    }
}