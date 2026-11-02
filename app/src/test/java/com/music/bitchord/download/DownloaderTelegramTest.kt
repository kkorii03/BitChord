package com.music.bitchord.download

import com.music.bitchord.telegram.TelegramAudioReader
import com.music.bitchord.telegram.TelegramFileInfo
import com.music.bitchord.telegram.TelegramFileTransport
import com.music.bitchord.telegram.TelegramTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Telegram source hands the downloader an in-process `bitchord-telegram:` URL
 * that no HTTP client accepts. Both the probe and the copy have to reach it
 * through TDLib's reader instead of OkHttp.
 */
class DownloaderTelegramTest {

    @After
    fun tearDown() {
        Downloader.telegramReaderOpener = null
        Downloader.telegramLeaseObserver = null
    }

    @Test
    fun `probe reads the head of a telegram url through the telegram reader`() = runBlocking {
        val data = "fLaC".toByteArray(Charsets.ISO_8859_1) + ByteArray(200) { it.toByte() }
        val transport = FakeTransport(data)
        var opened: Triple<String, Long, Long>? = null
        Downloader.telegramReaderOpener = { trackId, position, length ->
            opened = Triple(trackId, position, length)
            TelegramAudioReader.openWithTransport(
                trackId,
                position,
                length,
                TelegramAudioReader.REASON_VERIFY,
                transport,
            )
        }
        try {
            val probe = Downloader.probe(TELEGRAM_URL, emptyMap(), "flac")

            assertEquals(
                Downloader.Probe.File(Downloader.Container.FLAC, data.size.toLong()),
                probe,
            )
            assertEquals(Triple(TRACK_ID, 0L, 512L), opened)
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `a telegram download copies the whole file through the telegram reader`() = runBlocking {
        val data = ByteArray(10_000) { index -> (index * 7).toByte() }
        val transport = FakeTransport(data)
        val openedLengths = mutableListOf<Long>()
        Downloader.telegramReaderOpener = { trackId, position, length ->
            openedLengths += length
            TelegramAudioReader.openWithTransport(
                trackId,
                position,
                length,
                TelegramAudioReader.REASON_VERIFY,
                transport,
            )
        }
        try {
            val out = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()
            val written = Downloader.fetchDirect(TELEGRAM_URL, emptyMap(), out) { done, total ->
                assertEquals(data.size.toLong(), total)
                progress += done
            }

            assertEquals(data.size.toLong(), written)
            assertArrayEquals(data, out.toByteArray())
            assertEquals(listOf(-1L), openedLengths)
            assertEquals(data.size.toLong(), progress.last())
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `a telegram download keeps one balanced stream lease`() = runBlocking {
        val data = ByteArray(2_048) { index -> (index * 3).toByte() }
        val transport = FakeTransport(data)
        val leases = mutableListOf<Pair<String, Boolean>>()
        Downloader.telegramLeaseObserver = { trackId, begin -> leases += trackId to begin }
        Downloader.telegramReaderOpener = { trackId, position, length ->
            TelegramAudioReader.openWithTransport(
                trackId,
                position,
                length,
                TelegramAudioReader.REASON_VERIFY,
                transport,
            )
        }
        try {
            Downloader.fetchDirect(TELEGRAM_URL, emptyMap(), ByteArrayOutputStream()) { _, _ -> }

            assertEquals(listOf(TRACK_ID to true, TRACK_ID to false), leases)
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `a failed telegram open still ends the stream lease`() {
        val leases = mutableListOf<Pair<String, Boolean>>()
        Downloader.telegramLeaseObserver = { trackId, begin -> leases += trackId to begin }
        Downloader.telegramReaderOpener = { _, _, _ -> throw IOException("FILE_REFERENCE_EXPIRED") }

        val failure = assertThrows(IOException::class.java) {
            runBlocking {
                Downloader.fetchDirect(TELEGRAM_URL, emptyMap(), ByteArrayOutputStream()) { _, _ -> }
            }
        }

        assertEquals("FILE_REFERENCE_EXPIRED", failure.message)
        assertEquals(listOf(TRACK_ID to true, TRACK_ID to false), leases)
    }

    @Test
    fun `a telegram file swapped mid-download fails instead of mixing bytes`() {
        val original = ByteArray(WINDOW_BYTES + 1_000) { 0x11 }
        val replacement = ByteArray(WINDOW_BYTES + 1_000) { 0x22 }
        val transport = SwappingTransport(original, replacement, failAtCall = 2)
        Downloader.telegramReaderOpener = { trackId, position, length ->
            TelegramAudioReader.openWithTransport(
                trackId,
                position,
                length,
                TelegramAudioReader.REASON_VERIFY,
                transport,
            )
        }
        try {
            val out = ByteArrayOutputStream()
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    Downloader.fetchDirect(TELEGRAM_URL, emptyMap(), out) { _, _ -> }
                }
            }

            assertTrue(
                "unexpected failure: ${failure.message}",
                failure.message.orEmpty().contains("Telegram file changed"),
            )
            // Only the first TDLib window was written; the replacement file's
            // bytes never reached the sink.
            assertEquals(WINDOW_BYTES.toLong(), out.size().toLong())
        } finally {
            transport.cleanup()
        }
    }

    private class FakeTransport(private val data: ByteArray) : TelegramFileTransport {
        private val file = File.createTempFile("telegram-download-", ".bin").apply { writeBytes(data) }

        @Volatile
        private var downloaded = false

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

        override fun resolveTrack(trackId: String): TelegramTrack = track

        override fun fileInfo(fileId: Int): TelegramFileInfo =
            TelegramFileInfo(data.size.toLong(), file.absolutePath)

        override fun downloadedPrefixSize(fileId: Int, offset: Long): Long =
            if (downloaded) (data.size - offset).coerceAtLeast(0L) else 0L

        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long) {
            downloaded = true
        }

        override fun localPath(fileId: Int): String = file.absolutePath

        override fun isChatSelected(chatId: Long): Boolean = true

        override fun cancelDownload(fileId: Int) = Unit

        override fun nowMillis(): Long = System.nanoTime() / 1_000_000

        fun cleanup() {
            file.delete()
        }
    }

    /**
     * Behaves like TDLib's progressive download: only the requested window is
     * on disk. At [failAtCall] it swaps in a same-size file with a new file id,
     * which is the refresh that used to corrupt the sink.
     */
    private class SwappingTransport(
        original: ByteArray,
        private val replacement: ByteArray,
        private val failAtCall: Int,
    ) : TelegramFileTransport {
        private var data = original
        private var file = File.createTempFile("telegram-download-", ".bin").apply { writeBytes(data) }
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
        private var calls = 0
        private var downloadedFrom = 0L
        private var downloadedEnd = 0L

        override fun resolveTrack(trackId: String): TelegramTrack = track

        override fun fileInfo(fileId: Int): TelegramFileInfo =
            TelegramFileInfo(data.size.toLong(), file.absolutePath)

        override fun downloadedPrefixSize(fileId: Int, offset: Long): Long =
            if (offset in downloadedFrom until downloadedEnd) downloadedEnd - offset else 0L

        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long) {
            calls++
            if (calls == failAtCall) {
                file.delete()
                data = replacement
                file = File.createTempFile("telegram-download-", ".bin").apply { writeBytes(data) }
                track = track.copy(fileId = 9, size = data.size.toLong())
                downloadedFrom = 0L
                downloadedEnd = 0L
                throw IOException("FILE_REFERENCE_EXPIRED")
            }
            downloadedFrom = offset
            downloadedEnd = offset + minOf(limit, data.size - offset)
        }

        override fun localPath(fileId: Int): String = file.absolutePath

        override fun isChatSelected(chatId: Long): Boolean = true

        override fun cancelDownload(fileId: Int) = Unit

        override fun nowMillis(): Long = System.nanoTime() / 1_000_000

        fun cleanup() {
            file.delete()
        }
    }

    private companion object {
        const val TRACK_ID = "1_2"
        const val TELEGRAM_URL = "bitchord-telegram://track/1_2"
        const val WINDOW_BYTES = 4 * 1024 * 1024
    }
}
