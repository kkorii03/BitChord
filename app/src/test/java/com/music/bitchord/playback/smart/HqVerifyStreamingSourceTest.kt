package com.music.bitchord.playback.smart

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The candidate head is decoded while it is still being written. A read that
 * has caught up with the copy must wait for the next bytes rather than treat
 * the current end as the end of the recording, and must still see a real EOF
 * (or the copy's failure) once the download has finished.
 */
class HqVerifyStreamingSourceTest {

    private lateinit var root: File
    private lateinit var file: File

    @Before
    fun setUp() {
        root = File(
            System.getProperty("java.io.tmpdir"),
            "bitchord-growing-${System.nanoTime()}",
        ).apply { mkdirs() }
        file = File(root, "candidate.bin").apply { writeBytes(ByteArray(0)) }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a read waits for bytes the copy has not written yet`() {
        val progress = DownloadProgress()
        val reader = GrowingFileReader(file, expectedSize = 8L, progress = progress, pollMs = 5L)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Int> {
                reader.readAt(0L, ByteArray(4), 0, 4)
            }
            Thread.sleep(100)
            assertFalse("the read returned before any bytes arrived", first.isDone)

            append(byteArrayOf(1, 2, 3, 4))
            assertEquals(4, first.get(2, TimeUnit.SECONDS))

            val second = pool.submit<Int> {
                reader.readAt(4L, ByteArray(4), 0, 4)
            }
            Thread.sleep(100)
            assertFalse("the read did not wait at the growing end", second.isDone)

            append(byteArrayOf(5, 6, 7, 8))
            progress.markFinished()
            assertEquals(4, second.get(2, TimeUnit.SECONDS))

            assertEquals(-1, reader.readAt(8L, ByteArray(1), 0, 1))
        } finally {
            pool.shutdownNow()
            reader.close()
        }
    }

    @Test
    fun `a finished copy with a read at the end reports eof`() {
        append(byteArrayOf(1, 2, 3))
        val progress = DownloadProgress().apply { markFinished() }
        val reader = GrowingFileReader(file, expectedSize = 3L, progress = progress, pollMs = 5L)
        try {
            assertEquals(3, reader.readAt(0L, ByteArray(3), 0, 3))
            assertEquals(-1, reader.readAt(3L, ByteArray(1), 0, 1))
        } finally {
            reader.close()
        }
    }

    @Test
    fun `a failed copy surfaces as an error instead of a short recording`() {
        append(byteArrayOf(1, 2, 3))
        val progress = DownloadProgress().apply { markFinished(IOException("connection reset")) }
        val reader = GrowingFileReader(file, expectedSize = 10L, progress = progress, pollMs = 5L)
        try {
            assertEquals(3, reader.readAt(0L, ByteArray(3), 0, 3))
            try {
                reader.readAt(3L, ByteArray(1), 0, 1)
                fail("a failed download must not look like EOF")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("connection reset"))
            }
        } finally {
            reader.close()
        }
    }

    @Test
    fun `a read across the write frontier waits for the rest instead of returning short`() {
        append(byteArrayOf(1, 2))
        val progress = DownloadProgress()
        val reader = GrowingFileReader(file, expectedSize = 4L, progress = progress, pollMs = 5L)
        try {
            val writer = Thread {
                Thread.sleep(50)
                append(byteArrayOf(3, 4))
                progress.markFinished()
            }
            writer.start()
            val buffer = ByteArray(4)
            assertEquals(4, reader.readAt(0L, buffer, 0, 4))
            assertTrue(buffer.contentEquals(byteArrayOf(1, 2, 3, 4)))
            writer.join(2_000)
        } finally {
            reader.close()
        }
    }

    @Test
    fun `the fetch window is the decode window`() {
        assertEquals(HqAudioVerify.COMPARE_SECONDS + 1.0, HqAudioVerify.FETCH_SECONDS, 0.0001)
        assertTrue(HqAudioVerify.FETCH_SECONDS < HqAudioVerify.COMPARE_SECONDS + 2.0)
    }

    private fun append(bytes: ByteArray) {
        FileOutputStream(file, true).use { it.write(bytes) }
    }
}
