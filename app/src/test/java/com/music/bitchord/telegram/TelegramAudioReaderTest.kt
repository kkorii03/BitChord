package com.music.bitchord.telegram

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
            assertEquals(1, transport.cancelCalls.get())
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
        private val data = ByteArray(256) { (it and 0x7f).toByte() }
        private val file = File.createTempFile("telegram-reader-", ".bin").apply { writeBytes(data) }
        private val track = TelegramTrack(
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

        fun cleanup() {
            file.delete()
        }
    }

    private companion object {
        const val TRACK_ID = "1_2"
    }
}
