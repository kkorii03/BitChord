package com.music.bitchord.download

import com.music.bitchord.telegram.TelegramAudioReader
import com.music.bitchord.telegram.TelegramFileInfo
import com.music.bitchord.telegram.TelegramFileTransport
import com.music.bitchord.telegram.TelegramTrack
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private companion object {
        const val TRACK_ID = "1_2"
        const val TELEGRAM_URL = "bitchord-telegram://track/1_2"
    }
}
