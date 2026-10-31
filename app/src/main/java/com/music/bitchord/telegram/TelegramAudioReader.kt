package com.music.bitchord.telegram

import org.drinkless.tdlib.TdApi
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Sequential byte reader over TDLib's progressive file download.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later) NativeAudio,
 * without zip/cue support. A read asks TDLib for just the range it needs with
 * `DownloadFile(..., synchronous = true)` and then reads that range from the
 * file TDLib has on disk. Nothing is loaded whole into memory and the
 * downloaded file is never rewritten or re-tagged.
 *
 * Seeking is a new position within the same open reader, so the player keeps
 * its Range contract while only the requested window is fetched.
 */
class TelegramAudioReader private constructor(
    val fileId: Int,
    private val totalSize: Long,
    val mimeType: String,
    private var position: Long,
    private val endPosition: Long,
) : Closeable {

    @Volatile
    private var closed = false
    private var released = false
    private var localPath: String? = null
    private var downloadedFrom = 0L
    private var downloadedEnd = 0L

    fun remaining(): Long = endPosition - position

    val total: Long get() = totalSize

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0
        checkOpen()
        if (position >= endPosition) return -1
        val wanted = minOf(length.toLong(), endPosition - position).toInt()
        val path = ensureDownloaded(position, wanted)
        val count = RandomAccessFile(path, "r").use { file ->
            file.seek(position)
            val available = (file.length() - position).coerceAtLeast(0L).toInt()
            val read = minOf(wanted, available)
            if (read <= 0) return -1
            file.readFully(buffer, offset, read)
            read
        }
        position += count
        return count
    }

    /**
     * Makes sure [length] bytes at [at] are on disk, fetching up to a small
     * window ahead so the next reads do not each start a download.
     */
    private fun ensureDownloaded(at: Long, length: Int): String {
        localPath?.let { path ->
            if (at >= downloadedFrom && at + length <= downloadedEnd) return path
        }
        var available = TelegramClientManager.request(
            TdApi.GetFileDownloadedPrefixSize(fileId, at),
            15_000,
        ).size
        if (available < length) {
            val window = minOf(DOWNLOAD_WINDOW_BYTES, totalSize - at).coerceAtLeast(length.toLong())
            TelegramClientManager.request(
                TdApi.DownloadFile(fileId, 32, at, window, true),
                TimeUnit.SECONDS.toMillis(40),
            )
            available = TelegramClientManager.request(
                TdApi.GetFileDownloadedPrefixSize(fileId, at),
                15_000,
            ).size
            if (available < length) throw IOException("Telegram download did not reach the read range.")
        }
        val file = TelegramClientManager.request(TdApi.GetFile(fileId), 15_000)
        val path = file.local?.path
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Telegram did not provide a local file.")
        localPath = path
        downloadedFrom = at
        downloadedEnd = at + available.coerceAtLeast(length.toLong())
        return path
    }

    private fun checkOpen() {
        if (closed) throw InterruptedIOException("Telegram read cancelled")
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Telegram read interrupted")
        }
        val (chat, _) = TelegramMessageMapper.parseId(trackId)
            ?: throw IOException("Invalid Telegram track id.")
        if (chat !in TelegramClientManager.selectedChatIds()) {
            throw IOException("The Telegram music chat was deselected.")
        }
    }

    override fun close() {
        if (released) return
        released = true
        closed = true
        TelegramLog.stream("close", fileId)
    }

    private var trackId: String = ""

    private constructor(
        trackId: String,
        fileId: Int,
        totalSize: Long,
        mimeType: String,
        position: Long,
        endPosition: Long,
    ) : this(fileId, totalSize, mimeType, position, endPosition) {
        this.trackId = trackId
    }

    companion object {
        /** Enough for a comfortable buffer, small enough never to be a whole track. */
        private const val DOWNLOAD_WINDOW_BYTES = 4L * 1024 * 1024

        fun open(trackId: String, requestedPosition: Long, requestedLength: Long): TelegramAudioReader {
            if (requestedPosition < 0 || requestedLength < -1) {
                throw IOException("Invalid Telegram audio range.")
            }
            // The DataSource has just claimed the stream and opened the gate;
            // wait for the session to report ready rather than polling it.
            runBlocking { TelegramClientManager.awaitReady() }
            val track = TelegramClientManager.trackForStream(trackId)
            val file = TelegramClientManager.request(TdApi.GetFile(track.fileId), 15_000)
            val size = file.size
            if (size < 0 || requestedPosition > size) {
                throw IOException("Telegram audio position is outside the file.")
            }
            val available = size - requestedPosition
            val end = requestedPosition +
                if (requestedLength < 0) available else minOf(requestedLength, available)
            val reader = TelegramAudioReader(
                trackId = trackId,
                fileId = track.fileId,
                totalSize = size,
                mimeType = track.mimeType.ifBlank { "application/octet-stream" },
                position = requestedPosition,
                endPosition = end,
            )
            TelegramLog.stream(if (requestedPosition > 0) "seek" else "open", track.fileId)
            return reader
        }
    }
}
