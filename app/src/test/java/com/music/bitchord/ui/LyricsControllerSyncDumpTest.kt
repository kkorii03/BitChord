package com.music.bitchord.ui

import android.app.Application
import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.playback.smart.SyncTestMode
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertFalse
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
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var external: File
    private var queuedDispatcher: QueuedDispatcher? = null

    private val storeField = LyricsTiming::class.java.getDeclaredField("store").apply {
        isAccessible = true
    }

    private val savedSources = AppSettings.lyricsSources.value
    private val savedOrder = AppSettings.lyricsSourceOrder.value
    private val savedSynced = AppSettings.syncedLyrics.value
    private val savedRecheck = AppSettings.syncSafetyDownloadRecheck.value
    private val savedEcho = TrackLog.echo
    private val savedDecode = SyncTestMode.decodeHead
    private var savedEmbedded: (suspend (Context, String) -> EmbeddedLyrics.Embedded?)? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        AppSettings.syncedLyrics.value = true
        AppSettings.lyricsSources.value = setOf(LyricsSource.LRCLIB)
        AppSettings.lyricsSourceOrder.value = listOf(LyricsSource.LRCLIB)
        AppSettings.syncSafetyDownloadRecheck.value = true
        directory = Files.createTempDirectory("bitchord-sync-dump").toFile()
        external = Files.createTempDirectory("bitchord-sync-dump-ext").toFile()
        File(external, "sync-test.flag").writeText("")
        storeField.set(LyricsTiming, null)
        context = FakeContext.create(FakePreferences(), directory)
        controller = LyricsController(
            TestApplication(context, external),
            scope,
        )
        savedEmbedded = controller.embeddedLyrics
    }

    @After
    fun tearDown() {
        runBlocking {
            // A test that failed before releasing its queued work would otherwise
            // leave cancellation continuations with nobody to run them.
            runCatching { queuedDispatcher?.runAll() }
            job.cancelAndJoin()
        }
        queuedDispatcher = null
        Dispatchers.resetMain()
        AppSettings.lyricsSources.value = savedSources
        AppSettings.lyricsSourceOrder.value = savedOrder
        AppSettings.syncedLyrics.value = savedSynced
        AppSettings.syncSafetyDownloadRecheck.value = savedRecheck
        TrackLog.echo = savedEcho
        SyncTestMode.decodeHead = savedDecode
        savedEmbedded?.let { controller.embeddedLyrics = it }
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

    @Test
    fun `a downloaded untimed sheet keeps its wav and is labelled from the file`() = runBlocking {
        val id = "localwav001"
        val localUri = "file:///music/$id.mp3"
        val logs = captureLog()
        val decoded = mutableListOf<String?>()
        SyncTestMode.decodeHead = { _, song ->
            decoded += song.localUri
            byteArrayOf(7, 6, 5)
        }
        LyricsOverrideStore.write(
            context,
            id,
            listOf(LyricLine(0L, "one"), LyricLine(0L, "two")),
            source = null,
        )

        controller.loadLyrics(id, "Song", "Artist", durationMs = 200_000L, localUri = localUri)
        withTimeout(5_000L) { controller.lyricsChecked.first { it } }
        val dump = withTimeout(5_000L) {
            var found: String? = null
            while (found == null) {
                found = synchronized(logs) {
                    logs.firstOrNull { it.contains("sync-dump: begin $id ") }
                }
                if (found == null) kotlinx.coroutines.delay(10)
            }
            checkNotNull(found)
        }
        val wav = File(File(external, "synctest"), "$id.wav")
        withTimeout(5_000L) {
            while (!wav.isFile) kotlinx.coroutines.delay(10)
        }

        assertTrue("expected a file label, got $dump", dump.contains("source=file:unknown@$id"))
        assertTrue("expected the untimed block, got $dump", dump.contains("base=none"))
        assertEquals(listOf(localUri), decoded)
        assertTrue(wav.readBytes().contentEquals(byteArrayOf(7, 6, 5)))
    }

    @Test
    fun `a sidecar with a provider but no id dumps and keeps its wav`() = runBlocking {
        val id = "sidecar0001"
        val logs = captureLog()
        val decoded = mutableListOf<String?>()
        SyncTestMode.decodeHead = { _, song ->
            decoded += song.localUri
            byteArrayOf(9, 8, 7)
        }
        val packageDir = File(directory, "offline").apply { mkdirs() }
        val playlist = File(packageDir, "stream.m3u8").apply { writeText("") }
        File(packageDir, "lyrics.lrc").writeText(
            "[re:BitChord LRCLIB]\n[00:00.00]one\n[00:00.00]two\n",
        )
        // The unit-test android.jar returns null from Uri.parse, so the sidecar
        // is read by path here; parseText is the same half withSource uses.
        val raw = requireNotNull(EmbeddedLyrics.sidecar(playlist.path))
        controller.embeddedLyrics = { _, _ -> EmbeddedLyrics.parseText(raw) }
        val localUri = playlist.toURI().toString()

        controller.loadLyrics(id, "Song", "Artist", durationMs = 200_000L, localUri = localUri)
        withTimeout(5_000L) { controller.lyricsChecked.first { it } }
        val dump = withTimeout(5_000L) {
            var found: String? = null
            while (found == null) {
                found = synchronized(logs) {
                    logs.firstOrNull { it.contains("sync-dump: begin $id ") }
                }
                if (found == null) kotlinx.coroutines.delay(10)
            }
            checkNotNull(found)
        }
        val wav = File(File(external, "synctest"), "$id.wav")
        withTimeout(5_000L) {
            while (!wav.isFile) kotlinx.coroutines.delay(10)
        }

        assertTrue("expected the file's provider and no id, got $dump", dump.contains("source=file:LRCLIB@unknown"))
        assertTrue("expected the untimed block, got $dump", dump.contains("base=none"))
        assertEquals(listOf(localUri), decoded)
        assertTrue(wav.readBytes().contentEquals(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `a newer sheet suppresses the older sheet's untimed dump`() = runBlocking {
        val id = "staledump001"
        val logs = captureLog()
        SyncTestMode.decodeHead = { _, _ -> null }
        val dispatcher = QueuedDispatcher()
        queuedDispatcher = dispatcher
        controller.syncDumpDispatcher = dispatcher
        LyricsOverrideStore.write(context, id, listOf(LyricLine(0L, "stale line")), source = null)
        controller.loadLyrics(
            id, "Song", "Artist", durationMs = 200_000L,
            localUri = "file:///music/${id}a.mp3",
        )
        withTimeout(5_000L) {
            while (dispatcher.pending() == 0) kotlinx.coroutines.delay(10)
        }
        LyricsOverrideStore.write(context, id, listOf(LyricLine(0L, "fresh line")), source = null)
        controller.loadLyrics(id, "Song", "Artist", durationMs = 200_000L)
        withTimeout(5_000L) {
            while (dispatcher.pending() < 2) kotlinx.coroutines.delay(10)
        }
        dispatcher.runAll()

        val shown = synchronized(logs) { logs.filter { it.contains("sync-dump: line $id") } }
        assertTrue("expected the newer sheet, got $logs", shown.any { it.contains("fresh line") })
        assertFalse("the older sheet must not be dumped, got $logs", shown.any { it.contains("stale line") })
    }

    /** Holds launched sync-dump work until [runAll], so a track switch can be interleaved. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = mutableListOf<Runnable>()
        private var open = false

        fun pending(): Int = synchronized(tasks) { tasks.size }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val runNow = synchronized(tasks) {
                if (open) {
                    true
                } else {
                    tasks += block
                    false
                }
            }
            // After release, cancellation and completion resumes run at once so
            // the test's tearDown join cannot wait on a queue nobody drains.
            if (runNow) block.run()
        }

        fun runAll() {
            while (true) {
                val task = synchronized(tasks) {
                    tasks.removeFirstOrNull() ?: run {
                        open = true
                        null
                    }
                } ?: return
                task.run()
            }
        }
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