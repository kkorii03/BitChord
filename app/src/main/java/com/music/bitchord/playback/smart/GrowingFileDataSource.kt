package com.music.bitchord.playback.smart

import android.media.MediaDataSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * The state of a candidate head that is still being written to disk.
 *
 * [readAt] of [GrowingFileReader] waits on this instead of spinning: the copy
 * loop calls [changed] after every write, and marks [finished] once the body
 * has ended or failed. Nothing here polls on a timer; a wait only happens
 * while the decoder is asking for bytes the network has not delivered yet.
 */
internal class DownloadProgress {
    @Volatile
    var finished: Boolean = false

    @Volatile
    var failure: String? = null

    private val monitor = Object()

    fun changed() {
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun markFinished(error: Throwable? = null) {
        failure = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName
        finished = true
        changed()
    }

    fun awaitChange(timeoutMs: Long) {
        synchronized(monitor) { monitor.wait(timeoutMs) }
    }
}

/**
 * A [RandomAccessFile] that can be read while it is still growing.
 *
 * This is the pipe that lets one decoder work on the candidate's opening
 * while the network is still delivering it. A read at or past the current end
 * blocks until more bytes arrive (or the download ends), so the platform
 * extractor sees the same byte sequence it would have seen in a complete
 * file. It never holds the file in memory.
 */
internal class GrowingFileReader(
    private val file: File,
    private val expectedSize: Long,
    private val progress: DownloadProgress,
    private val pollMs: Long = 50L,
) : AutoCloseable {

    private val reader = RandomAccessFile(file, "r")

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0) throw IOException("negative position $position")
        while (true) {
            val length = file.length()
            if (position < length) {
                val want = min(size.toLong(), length - position).toInt()
                reader.seek(position)
                return reader.read(buffer, offset, want)
            }
            if (progress.finished) {
                progress.failure?.let { throw IOException(it) }
                return -1
            }
            progress.awaitChange(pollMs)
        }
    }

    fun size(): Long = expectedSize.coerceAtLeast(0L)

    override fun close() {
        runCatching { reader.close() }
    }
}

/** [GrowingFileReader] as the platform extractor's data source. */
internal class GrowingFileDataSource(
    private val reader: GrowingFileReader,
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.size()

    override fun close() = reader.close()
}
