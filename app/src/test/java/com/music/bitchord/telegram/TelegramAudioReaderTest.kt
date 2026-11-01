package com.music.bitchord.telegram

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Range reads over TDLib are tested without a native session: a transport
 * fake stands in for GetFile/DownloadFile and writes the bytes the reader
 * would find on disk.
 */
class TelegramAudioReaderTest {

    @Test
    fun `an expired file reference is retried once after re-reading the message`() {
        val transport = FakeTransport()
        transport.failDownloadAttempts = 1
        try {
            val reader = open(transport)
            val buffer = ByteArray(64)
            assertEquals(64, reader.read(buffer, 0, buffer.size))
            assertEquals(2, transport.downloadCalls.get())
            assertEquals(2, transport.resolveCalls.get())
            reader.close()
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `a permanently deleted file fails after exactly one retry`() {
        val transport = FakeTransport()
        transport.failDownloadsAlways = true
        try {
            val reader = open(transport)
            assertThrows(IOException::class.java) {
                reader.read(ByteArray(64), 0, 64)
            }
            assertEquals(2, transport.downloadCalls.get())
            assertEquals(2, transport.resolveCalls.get())
            reader.close()
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `a download that never reaches the range fails after one retry`() {
        val transport = FakeTransport()
        transport.markDownloaded = false
        try {
            val reader = open(transport)
            assertThrows(IOException::class.java) {
                reader.read(ByteArray(64), 0, 64)
            }
            assertEquals(2, transport.downloadCalls.get())
            reader.close()
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `closing a reader cancels the range download it owns`() {
        val transport = FakeTransport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.downloadAction = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        transport.cancelAction = { release.countDown() }
        try {
            val reader = open(transport)
            val failure = AtomicReference<Throwable>()
            val worker = thread(name = "telegram-reader-close") {
                try {
                    reader.read(ByteArray(64), 0, 64)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertTrue("download did not start", entered.await(5, TimeUnit.SECONDS))
            reader.close()
            worker.join(5_000)
            assertFalse("read did not return after close", worker.isAlive)
            // close() cancels, and the download's own exit cancels again in case
            // TDLib had not registered it yet; a repeated cancel is harmless.
            assertTrue(transport.cancelCalls.get() >= 1)
            assertNotNull(failure.get())
            transport.cleanup()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `verification and playback on the same file do not cancel each other`() {
        val transport = FakeTransport()
        val firstEntered = CountDownLatch(1)
        val firstStarted = java.util.concurrent.atomic.AtomicBoolean()
        val releaseFirst = CountDownLatch(1)
        val concurrent = AtomicInteger()
        val maximum = AtomicInteger()
        transport.downloadAction = {
            val live = concurrent.incrementAndGet()
            maximum.accumulateAndGet(live, ::maxOf)
            try {
                if (!firstStarted.getAndSet(true)) {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
            } finally {
                concurrent.decrementAndGet()
            }
        }
        try {
            val playback = open(transport, reason = TelegramAudioReader.REASON_PLAY)
            val verify = open(transport, reason = TelegramAudioReader.REASON_VERIFY)
            val played = thread(name = "telegram-play") {
                runCatching { playback.read(ByteArray(64), 0, 64) }
            }
            assertTrue("first download did not start", firstEntered.await(5, TimeUnit.SECONDS))
            val verified = thread(name = "telegram-verify") {
                runCatching { verify.read(ByteArray(64), 0, 64) }
            }
            Thread.sleep(200)
            releaseFirst.countDown()
            played.join(5_000)
            verified.join(5_000)
            assertFalse(played.isAlive)
            assertFalse(verified.isAlive)
            assertEquals("TDLib has one download per file; ranges must be serialised", 1, maximum.get())
            playback.close()
            verify.close()
            transport.cleanup()
        } finally {
            releaseFirst.countDown()
        }
    }

    @Test
    fun `refreshing after the file size changes uses the new length and end`() {
        val transport = FakeTransport()
        transport.failDownloadAttempts = 1
        try {
            val reader = open(transport)
            val replacement = ByteArray(100) { ((it * 3) and 0x7f).toByte() }
            transport.replaceFile(fileId = 9, content = replacement)

            val buffer = ByteArray(256)
            assertEquals(100, reader.read(buffer, 0, buffer.size))
            assertArrayEquals(replacement, buffer.copyOf(100))
            assertEquals(100L, reader.total)
            assertEquals(0L, reader.remaining())
            assertEquals(-1, reader.read(buffer, 0, buffer.size))
            reader.close()
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `refreshing to a file shorter than the current position fails cleanly`() {
        val transport = FakeTransport()
        transport.failDownloadAttempts = 1
        try {
            val reader = TelegramAudioReader.openWithTransport(
                trackId = TRACK_ID,
                requestedPosition = 200,
                requestedLength = -1,
                reason = TelegramAudioReader.REASON_PLAY,
                transport = transport,
            )
            transport.replaceFile(fileId = 9, content = ByteArray(100))

            val failure = assertThrows(IOException::class.java) {
                reader.read(ByteArray(16), 0, 16)
            }
            assertTrue(
                failure.suppressed.any { it.message?.contains("outside the file") == true },
            )
            reader.close()
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `readers on one track share a lock that is removed when they finish`() {
        val transport = FakeTransport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = java.util.concurrent.atomic.AtomicBoolean()
        transport.downloadAction = {
            if (blocked.compareAndSet(false, true)) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            val first = open(transport)
            val second = open(transport)
            val firstRead = thread(name = "telegram-first") {
                runCatching { first.read(ByteArray(64), 0, 64) }
            }
            assertTrue("download did not start", entered.await(5, TimeUnit.SECONDS))
            assertEquals(1, TelegramAudioReader.downloadLockCount())
            val secondRead = thread(name = "telegram-second") {
                runCatching { second.read(ByteArray(64), 0, 64) }
            }
            Thread.sleep(100)
            assertEquals(1, TelegramAudioReader.downloadLockCount())
            release.countDown()
            firstRead.join(5_000)
            secondRead.join(5_000)
            assertFalse(firstRead.isAlive)
            assertFalse(secondRead.isAlive)
            assertEquals(0, TelegramAudioReader.downloadLockCount())
            first.close()
            second.close()
            assertEquals(0, TelegramAudioReader.downloadLockCount())
        } finally {
            release.countDown()
            transport.cleanup()
        }
    }

    private fun open(
        transport: FakeTransport,
        reason: String = TelegramAudioReader.REASON_PLAY,
    ): TelegramAudioReader = TelegramAudioReader.openWithTransport(
        trackId = TRACK_ID,
        requestedPosition = 0,
        requestedLength = -1,
        reason = reason,
        transport = transport,
    )

    private class FakeTransport : TelegramFileTransport {
        @Volatile
        private var data = ByteArray(256) { (it and 0x7f).toByte() }

        @Volatile
        private var file = File.createTempFile("telegram-reader-", ".bin").apply { writeBytes(data) }

        @Volatile
        private var track = TelegramTrack(
            id = TRACK_ID,
            chatId = 1L,
            messageId = 2L,
            fileId = 7,
            size = data.size.toLong(),
            title = "Title",
            artist = "Artist",
            durationSec = 1,
            format = "flac",
            mimeType = "audio/flac",
            fileName = "Artist - Title.flac",
        )

        @Volatile
        private var downloaded = false

        @Volatile
        var failDownloadsAlways = false
        var failDownloadAttempts = 0
        var markDownloaded = true
        var downloadAction: (() -> Unit)? = null
        var cancelAction: (() -> Unit)? = null

        val downloadCalls = AtomicInteger()
        val resolveCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()

        override fun resolveTrack(trackId: String): TelegramTrack {
            resolveCalls.incrementAndGet()
            return track
        }

        override fun fileInfo(fileId: Int): TelegramFileInfo =
            TelegramFileInfo(data.size.toLong(), file.absolutePath)

        override fun downloadedPrefixSize(fileId: Int, offset: Long): Long =
            if (downloaded) (data.size - offset).coerceAtLeast(0L) else 0L

        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long) {
            downloadCalls.incrementAndGet()
            downloadAction?.invoke()
            if (failDownloadsAlways) throw IOException("FILE_REFERENCE_EXPIRED")
            if (failDownloadAttempts > 0) {
                failDownloadAttempts--
                throw IOException("FILE_REFERENCE_EXPIRED")
            }
            if (markDownloaded) downloaded = true
        }

        override fun localPath(fileId: Int): String = file.absolutePath

        override fun isChatSelected(chatId: Long): Boolean = true

        override fun cancelDownload(fileId: Int) {
            cancelCalls.incrementAndGet()
            cancelAction?.invoke()
        }

        override fun nowMillis(): Long = System.nanoTime() / 1_000_000

        /** Swaps the message's file for a replacement with a different size. */
        fun replaceFile(fileId: Int, content: ByteArray) {
            file.delete()
            data = content
            file = File.createTempFile("telegram-reader-", ".bin").apply { writeBytes(content) }
            track = track.copy(fileId = fileId, size = content.size.toLong())
            downloaded = false
        }

        fun cleanup() {
            file.delete()
        }
    }

    private companion object {
        const val TRACK_ID = "1_2"
    }
}
