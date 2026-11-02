package com.music.bitchord.playback.smart

import android.app.Application
import android.content.Context
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two test-mode writers can keep the same song at once (a song check and the
 * play-time head), and the WAV write must not hold the object monitor that the
 * main-thread dump report needs.
 */
class SyncTestModeWriteTest {

    private val savedDecode = SyncTestMode.decodeHead
    private lateinit var directory: File
    private lateinit var external: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-sync-write").toFile()
        external = Files.createTempDirectory("bitchord-sync-write-ext").toFile()
        File(external, "sync-test.flag").writeText("")
        context = TestApplication(FakeContext.create(FakePreferences(), directory), external)
    }

    @After
    fun tearDown() {
        SyncTestMode.decodeHead = savedDecode
        directory.deleteRecursively()
        external.deleteRecursively()
    }

    @Test
    fun `concurrent saveWav and keepHead leave one complete wav`() = runBlocking {
        val id = "racedwav001"
        val first = ByteArray(512 * 1024) { 0x11 }
        val second = ByteArray(512 * 1024) { 0x22 }
        SyncTestMode.decodeHead = { _, _ -> second }
        val wav = File(File(external, "synctest"), "$id.wav")
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                wav.delete()
                val barrier = CyclicBarrier(2)
                val written = if (round % 2 == 0) first else second
                val check = pool.submit {
                    barrier.await()
                    SyncTestMode.saveWav(context, id, written)
                }
                val head = pool.submit {
                    barrier.await()
                    runBlocking { SyncTestMode.keepHead(context, id, null) }
                }
                check.get(10, TimeUnit.SECONDS)
                head.get(10, TimeUnit.SECONDS)

                assertTrue("round $round: no wav", wav.isFile)
                val bytes = wav.readBytes()
                assertEquals("round $round: partial wav", first.size, bytes.size)
                assertTrue(
                    "round $round: mixed wav",
                    bytes.contentEquals(first) || bytes.contentEquals(second),
                )
            }
        } finally {
            pool.shutdownNow()
        }

        val kept = File(external, "synctest").listFiles().orEmpty()
        assertEquals("expected exactly one wav", 1, kept.count { it.name.endsWith(".wav") })
        assertEquals("temp files were left behind", 0, kept.count { it.name.endsWith(".tmp") })
    }

    @Test
    fun `a wav write does not block the object monitor the dump uses`() {
        val id = "lockwav0001"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = BlockingApplication(external, entered, release)
        // Cache the flag answer first, so the writer's enabled() call does not
        // touch the blocking external directory and only write() does.
        assertTrue(SyncTestMode.enabled(blocking, id))

        val writer = Thread(
            { SyncTestMode.saveWav(blocking, id, byteArrayOf(1, 2, 3)) },
            "wav-writer",
        ).apply { start() }
        assertTrue("write never started", entered.await(5, TimeUnit.SECONDS))

        val probe = Thread({ SyncTestMode.enabled(blocking, id) }, "dump-probe").apply { start() }
        probe.join(1_000)
        val blocked = probe.isAlive

        release.countDown()
        writer.join(5_000)
        probe.join(5_000)

        assertFalse("a WAV write held the object monitor", blocked)
    }

    private class BlockingApplication(
        private val external: File,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : Application() {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File {
            if (Thread.currentThread().name == "wav-writer") {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return external
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
