package com.music.bitchord.playback

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * One committed cache span: [length] bytes starting at [position] live at the
 * start of [file].
 *
 * Only spans SimpleCache has already committed are ever built into this type.
 * A span still being written has no committed length, so its bytes must not be
 * handed to a decoder as if they were part of a finished file.
 */
internal data class CommittedSpan(val position: Long, val length: Long, val file: File) {
    val end: Long get() = position + length
}

/** The committed span covering [position], if any. */
internal fun committedSpanAt(spans: List<CommittedSpan>, position: Long): CommittedSpan? =
    spans.firstOrNull { position >= it.position && position < it.end }

/**
 * Reads a rendition from its committed span files without touching SimpleCache.
 *
 * Background decodes used to open a [androidx.media3.datasource.cache.CacheDataSource],
 * whose every open calls back into SimpleCache and may take its monitor while
 * it touches the span and stores the content index. A decode that seeks around
 * a container did that repeatedly, and the player's own next open then waited
 * on the monitor. Reading the span files directly, after one short snapshot of
 * [androidx.media3.datasource.cache.Cache.getCachedSpans], keeps the decoder
 * off that lock entirely.
 *
 * A hole between spans reads as end of input rather than as a different
 * rendering of the file, and a read never crosses bytes a span has not
 * committed.
 */
internal class CommittedSpanReader(
    private val spans: List<CommittedSpan>,
    private val contentLength: Long,
) : AutoCloseable {

    private val readers = HashMap<File, RandomAccessFile>()

    fun size(): Long =
        contentLength.takeIf { it > 0L } ?: spans.maxOfOrNull { it.end } ?: -1L

    @Synchronized
    fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0 || position < 0L) return if (size == 0) 0 else -1
        var filled = 0
        var at = position
        while (filled < size) {
            val span = committedSpanAt(spans, at) ?: break
            val within = at - span.position
            val want = minOf((size - filled).toLong(), span.length - within).toInt()
            if (want <= 0) break
            val reader = readerFor(span.file) ?: break
            val read = try {
                reader.seek(within)
                reader.read(buffer, offset + filled, want)
            } catch (_: IOException) {
                break
            }
            if (read <= 0) break
            filled += read
            at += read
        }
        return if (filled == 0) -1 else filled
    }

    private fun readerFor(file: File): RandomAccessFile? =
        readers[file] ?: runCatching { RandomAccessFile(file, "r") }
            .getOrNull()
            ?.also { readers[file] = it }

    @Synchronized
    override fun close() {
        readers.values.forEach { runCatching { it.close() } }
        readers.clear()
    }
}
