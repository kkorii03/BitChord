package com.music.bitchord.download

import com.music.bitchord.telegram.TelegramAudioReader
import com.music.bitchord.telegram.TelegramFileInfo
import com.music.bitchord.telegram.TelegramFileTransport
import com.music.bitchord.telegram.TelegramTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A Telegram candidate is not an HTTP URL. Downloads used to hand its internal
 * scheme to OkHttp, which refused it ("Expected URL scheme 'http' or 'https'"),
 * so every lossless upgrade from Telegram failed. These tests stand in a
 * transport-backed reader and assert both the 512-byte probe and the whole
 * download go through it.
 */
class DownloaderTelegramTest {

    private val url = "bitchord-telegram://track/$TRACK_ID"

    @Test
    fun `probe reads the opening of a Telegram stream through the reader`() = runBlocking {
        val data = flac(256)
        val transport = FakeTransport(data)
        try {
            val opener = RecordingOpener(transport)
            val probe = Downloader.probe(url, emptyMap(), statedCodec = "flac", telegramOpener = opener)

            assertEquals(Downloader.Probe.File(Downloader.Container.FLAC, data.size.toLong()), probe)
            assertEquals(TRACK_ID, opener.trackId)
            assertEquals(0L, opener.position)
            assertEquals(512L, opener.requestedLength)
            assertEquals(TelegramAudioReader.REASON_VERIFY, opener.reason)
        } finally {
            transport.cleanup()
        }
    }

    @Test
    fun `fetchDirect writes a whole Telegram stream through the reader`() = runBlocking {
        val data = flac(4096)
        val transport = FakeTransport(data)
        try {
            val opener = RecordingOpener(transport)
            val out = ByteArrayOutputStream()
            var progressTotal = -1L

            val written = Downloader.fetchDirect(
                url = url,
                headers = emptyMap(),
                sink = out,
                onProgress = { _, total -> progressTotal = total },
                telegramOpener = opener,
            )

            assertEquals(data.size.toLong(), written)
            assertArrayEquals(data, out.toByteArray())
            assertEquals(data.size.toLong(), progressTotal)
            assertEquals(0L, opener.position)
            assertEquals(-1L, opener.requestedLength)
        } finally {
            transport.cleanup()
        }
    }

    private fun flac(size: Int): ByteArray =
        ByteArray(size) { (it * 31).toByte() }.also {
            "fLaC".forEachIndexed { index, char -> it[index] = char.code.toByte() }
        }

    private class RecordingOpener(private val transport: FakeTransport) : Downloader.TelegramStreamOpener {
        var trackId: String? = null
        var position = -2L
        var requestedLength = -2L
        var reason: String? = null

        override suspend fun open(
            trackId: String,
            position: Long,
            requestedLength: Long,
            reason: String,
        ): TelegramAudioReader {
            this.trackId = trackId
            this.position = position
            this.requestedLength = requestedLength
            this.reason = reason
            return TelegramAudioReader.openWithTransport(
                trackId = trackId,
                requestedPosition = position,
                requestedLength = requestedLength,
                reason = reason,
                transport = transport,
            )
        }
    }

    private class FakeTransport(private val data: ByteArray) : TelegramFileTransport {
        private val file = File.createTempFile("download-telegram-", ".bin").apply { writeBytes(data) }

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
            (data.size - offset).coerceAtLeast(0L)

        override fun download(fileId: Int, priority: Int, offset: Long, limit: Long) = Unit

        override fun localPath(fileId: Int): String = file.absolutePath

        override fun isChatSelected(chatId: Long): Boolean = true

        override fun cancelDownload(fileId: Int) = Unit

        override fun nowMillis(): Long = System.nanoTime() / 1_000_000

        fun cleanup() {
            file.delete()
        }
    }

    private companion object {
        const val TRACK_ID = "-1001234567890_42"
    }
}
