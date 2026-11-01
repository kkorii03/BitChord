package com.music.bitchord.telegram

import org.drinkless.tdlib.TdApi
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
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
 *
 * TDLib keeps one download per file: a second `DownloadFile` with a different
 * offset/limit cancels the first. Verification and playback may ask for the
 * same file at the same time, so downloads are serialised per track here and
 * only the reader that actually owns a download cancels it on close. A failed
 * download is retried once after re-reading the message, which is what
 * refreshes an expired file reference; a deleted message fails cleanly after
 * that one retry instead of waiting forever.
 */
class TelegramAudioReader internal constructor(
    private val trackId: String,
    fileId: Int,
    totalSize: Long,
    mimeType: String,
    private var position: Long,
    private val requestedLength: Long,
    private val reason: String,
    private val transport: TelegramFileTransport,
) : Closeable {

    @Volatile
    var fileId: Int = fileId
        private set

    @Volatile
    private var totalSize = totalSize

    @Volatile
    var mimeType: String = mimeType
        private set

    private val startPosition = position

    @Volatile
    private var endPosition = endFor(startPosition, totalSize, requestedLength)

    @Volatile
    private var closed = false
    private var released = false
    private var localPath: String? = null
    private var downloadedFrom = 0L
    private var downloadedEnd = 0L
    private val downloading = AtomicBoolean(false)

    fun remaining(): Long = endPosition - position

    val total: Long get() = totalSize

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0
        checkOpen()
        if (position >= endPosition) return -1
        // Refreshing the message may replace the file with a different size and
        // move endPosition; only read what the new end still allows.
        val path = ensureDownloaded(position, length) ?: return -1
        val wanted = minOf(length.toLong(), endPosition - position).toInt()
        if (wanted <= 0) return -1
        val count = RandomAccessFile(path, "r").use { file ->
            file.seek(position)
            val available = (file.length() - position)
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
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
    private fun ensureDownloaded(at: Long, length: Int): String? {
        val wanted = minOf(length.toLong(), endPosition - at).toInt()
        if (wanted <= 0) return null
        localPath?.let { path ->
            if (at >= downloadedFrom && at + wanted <= downloadedEnd) return path
        }
        val entry = acquireDownloadLock(trackId)
        entry.lock.lock()
        try {
            checkOpen()
            // A reader that was waiting for the per-track lock may now find the
            // range it wanted already on disk.
            val lockedWanted = minOf(length.toLong(), endPosition - at).toInt()
            if (lockedWanted <= 0) return null
            localPath?.let { path ->
                if (at >= downloadedFrom && at + lockedWanted <= downloadedEnd) return path
            }
            var attempt = 0
            while (true) {
                checkOpen()
                // refreshTrack may have changed the file, so clamp each attempt
                // to the end position that is valid now.
                val currentWanted = minOf(length.toLong(), endPosition - at).toInt()
                if (currentWanted <= 0) return null
                try {
                    return downloadRange(at, currentWanted)
                } catch (failure: Exception) {
                    if (closed) throw InterruptedIOException("Telegram read cancelled")
                    if (failure is InterruptedIOException) throw failure
                    if (attempt >= MAX_DOWNLOAD_ATTEMPTS - 1) throw failure
                    attempt++
                    try {
                        refreshTrack()
                    } catch (refreshFailure: Exception) {
                        failure.addSuppressed(refreshFailure)
                        throw failure
                    }
                }
            }
        } finally {
            entry.lock.unlock()
            releaseDownloadLock(trackId, entry)
        }
    }

    private fun downloadRange(at: Long, length: Int): String {
        var available = transport.downloadedPrefixSize(fileId, at)
        if (available < length) {
            val window = minOf(DOWNLOAD_WINDOW_BYTES, totalSize - at)
                .coerceAtLeast(length.toLong())
            val started = transport.nowMillis()
            val before = available
            downloading.set(true)
            try {
                checkOpen()
                transport.download(fileId, priority(reason), at, window)
            } finally {
                downloading.set(false)
                // close() may have sent its cancel before TDLib registered this
                // DownloadFile; cancel again so the window stops with the reader.
                if (closed) runCatching { transport.cancelDownload(fileId) }
            }
            checkOpen()
            available = transport.downloadedPrefixSize(fileId, at)
            val millis = (transport.nowMillis() - started).coerceAtLeast(0L)
            val bytes = (available - before).coerceAtLeast(0L)
            TelegramLog.download(fileId, bytes, millis, reason)
            if (available < length) {
                throw IOException("Telegram download did not reach the read range.")
            }
        }
        val path = transport.localPath(fileId)
            ?: throw IOException("Telegram did not provide a local file.")
        localPath = path
        downloadedFrom = at
        downloadedEnd = at + available.coerceAtLeast(length.toLong())
        return path
    }

    /**
     * A deleted message/channel ends the retry; a refreshed message updates the
     * file id and also the size/mime/end that belong to the replacement file.
     */
    private fun refreshTrack() {
        val fresh = transport.resolveTrack(trackId)
        val freshSize = transport.fileInfo(fresh.fileId).size
        if (freshSize < 0 || position > freshSize) {
            throw IOException("Telegram audio position is outside the file.")
        }
        if (fresh.fileId != fileId) {
            fileId = fresh.fileId
            localPath = null
            downloadedFrom = 0L
            downloadedEnd = 0L
        }
        totalSize = freshSize
        mimeType = fresh.mimeType.ifBlank { "application/octet-stream" }
        endPosition = endFor(startPosition, freshSize, requestedLength)
    }

    private fun endFor(start: Long, size: Long, length: Long): Long =
        start + if (length < 0) size - start else minOf(length, size - start)

    private fun checkOpen() {
        if (closed) throw InterruptedIOException("Telegram read cancelled")
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Telegram read interrupted")
        }
        val (chat, _) = TelegramMessageMapper.parseId(trackId)
            ?: throw IOException("Invalid Telegram track id.")
        if (!transport.isChatSelected(chat)) {
            throw IOException("The Telegram music chat was deselected.")
        }
    }

    override fun close() {
        if (released) return
        released = true
        closed = true
        // A synchronous DownloadFile is answered by TDLib only when it is done
        // or cancelled. The close event is what cancels the range this reader
        // owns; a reader waiting on another download never cancels that one.
        if (downloading.getAndSet(false)) {
            runCatching { transport.cancelDownload(fileId) }
        }
        TelegramLog.stream("close", fileId)
    }

    private fun priority(reason: String): Int =
        if (reason == REASON_VERIFY) VERIFY_PRIORITY else PLAY_PRIORITY

    companion object {
        /** Enough for a comfortable buffer, small enough never to be a whole track. */
        private const val DOWNLOAD_WINDOW_BYTES = 4L * 1024 * 1024

        /** Playback outranks a comparison, but both remain usable at once. */
        private const val PLAY_PRIORITY = 32
        private const val VERIFY_PRIORITY = 16

        internal const val REASON_PLAY = "play"
        internal const val REASON_VERIFY = "verify"

        /** One retry after refreshing the message; a deleted file then fails cleanly. */
        private const val MAX_DOWNLOAD_ATTEMPTS = 2

        /**
         * TDLib has one download per file. The key is the track (the message
         * the listener chose), so a new file id after a reference refresh still
         * shares the same lock with the playback that was already reading it.
         * The entry is removed as soon as the last holder or waiter leaves, so
         * a history of played tracks cannot grow this map forever.
         */
        private val downloadLocks = ConcurrentHashMap<String, DownloadLock>()

        private class DownloadLock {
            val lock = ReentrantLock()
            var users = 0
        }

        private fun acquireDownloadLock(trackId: String): DownloadLock =
            downloadLocks.compute(trackId) { _, existing ->
                (existing ?: DownloadLock()).also { it.users++ }
            }!!

        private fun releaseDownloadLock(trackId: String, entry: DownloadLock) {
            downloadLocks.computeIfPresent(trackId) { _, existing ->
                if (existing !== entry) return@computeIfPresent existing
                existing.users--
                if (existing.users <= 0) null else existing
            }
        }

        internal fun downloadLockCount(): Int = downloadLocks.size

        fun open(
            trackId: String,
            requestedPosition: Long,
            requestedLength: Long,
        ): TelegramAudioReader = open(trackId, requestedPosition, requestedLength, REASON_PLAY)

        /**
         * [reason] is [REASON_VERIFY] for a sound-comparison head and
         * [REASON_PLAY] for the player. It only sets the download priority and
         * the one diagnostic line, never the audio that is fetched.
         */
        fun open(
            trackId: String,
            requestedPosition: Long,
            requestedLength: Long,
            reason: String,
        ): TelegramAudioReader {
            // The DataSource has just claimed the stream and opened the gate;
            // wait for the session to report ready rather than polling it.
            runBlocking { TelegramClientManager.awaitReady() }
            return openWithTransport(
                trackId,
                requestedPosition,
                requestedLength,
                reason,
                SystemTelegramFileTransport,
            )
        }

        internal fun openWithTransport(
            trackId: String,
            requestedPosition: Long,
            requestedLength: Long,
            reason: String,
            transport: TelegramFileTransport,
        ): TelegramAudioReader {
            if (requestedPosition < 0 || requestedLength < -1) {
                throw IOException("Invalid Telegram audio range.")
            }
            val resolved = resolve(trackId, transport)
            val size = resolved.info.size
            if (size < 0 || requestedPosition > size) {
                throw IOException("Telegram audio position is outside the file.")
            }
            val reader = TelegramAudioReader(
                trackId = trackId,
                fileId = resolved.track.fileId,
                totalSize = size,
                mimeType = resolved.track.mimeType.ifBlank { "application/octet-stream" },
                position = requestedPosition,
                requestedLength = requestedLength,
                reason = reason,
                transport = transport,
            )
            TelegramLog.stream(if (requestedPosition > 0) "seek" else "open", reader.fileId)
            return reader
        }

        /** Re-reads the message once before giving up, refreshing its file reference. */
        private fun resolve(
            trackId: String,
            transport: TelegramFileTransport,
        ): Resolved {
            var attempt = 0
            while (true) {
                try {
                    val track = transport.resolveTrack(trackId)
                    return Resolved(track, transport.fileInfo(track.fileId))
                } catch (failure: Exception) {
                    if (failure is InterruptedIOException) throw failure
                    if (attempt >= MAX_DOWNLOAD_ATTEMPTS - 1) throw failure
                    attempt++
                }
            }
        }

        private data class Resolved(val track: TelegramTrack, val info: TelegramFileInfo)
    }
}

/** The bytes TDLib currently has for one file. */
internal data class TelegramFileInfo(val size: Long, val path: String?)

/**
 * Narrow seam over the TDLib client, so range reads, retry, cancellation and
 * serialisation can be tested without a native session.
 */
internal interface TelegramFileTransport {
    fun resolveTrack(trackId: String): TelegramTrack
    fun fileInfo(fileId: Int): TelegramFileInfo
    fun downloadedPrefixSize(fileId: Int, offset: Long): Long
    fun download(fileId: Int, priority: Int, offset: Long, limit: Long)
    fun localPath(fileId: Int): String?
    fun isChatSelected(chatId: Long): Boolean
    fun cancelDownload(fileId: Int)
    fun nowMillis(): Long
}

private object SystemTelegramFileTransport : TelegramFileTransport {

    override fun resolveTrack(trackId: String): TelegramTrack =
        TelegramClientManager.trackForStream(trackId)

    override fun fileInfo(fileId: Int): TelegramFileInfo {
        val file = TelegramClientManager.request(TdApi.GetFile(fileId), 15_000)
        return TelegramFileInfo(
            size = file.size,
            path = file.local?.path?.takeIf { it.isNotBlank() },
        )
    }

    override fun downloadedPrefixSize(fileId: Int, offset: Long): Long =
        TelegramClientManager.request(
            TdApi.GetFileDownloadedPrefixSize(fileId, offset),
            15_000,
        ).size

    override fun download(fileId: Int, priority: Int, offset: Long, limit: Long) {
        TelegramClientManager.request(
            TdApi.DownloadFile(fileId, priority, offset, limit, true),
            TimeUnit.SECONDS.toMillis(40),
        )
    }

    override fun localPath(fileId: Int): String? = fileInfo(fileId).path

    override fun isChatSelected(chatId: Long): Boolean =
        chatId in TelegramClientManager.selectedChatIds()

    override fun cancelDownload(fileId: Int) = TelegramClientManager.cancelDownload(fileId)

    override fun nowMillis(): Long = System.nanoTime() / 1_000_000
}
