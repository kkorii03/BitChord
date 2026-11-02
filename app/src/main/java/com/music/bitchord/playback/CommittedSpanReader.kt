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
 *
 * Span files are opened as soon as the reader is built. The cache may evict a
 * span at any point after the snapshot, and a file opened only when its first
 * byte is read can be gone by then; an already-open descriptor keeps reading
 * the data the evictor unlinked. A file missing at construction is skipped,
 * so a decode only ever sees what was committed when it started — including
 * in [size], so it never advertises bytes [read] cannot return.
 */
internal class CommittedSpanReader(
    spans: List<CommittedSpan>,
    private val contentLength: Long,
) : AutoCloseable {

    private val readers = HashMap<File, RandomAccessFile>()
    private val spans: List<CommittedSpan>

    init {
        spans.map { it.file }.distinct().forEach { open(it) }
        // Keep only spans whose file opened: a snapshot can name a span the
        // evictor already removed, and its bytes are not readable.
        this.spans = spans.filter { readers.containsKey(it.file) }
    }

    /**
     * The declared length only once the committed bytes actually reach it;
     * otherwise the contiguous committed end, or `-1` when nothing starts at
     * byte 0.
     *
     * A partial rendition told its full length would let the extractor seek
     * past the bytes it can read, and the decode then fails with no first
     * sample. Holes count: bytes committed after a gap are not a longer file.
     */
    fun size(): Long {
        val committedEnd = contiguousEnd()
        return if (contentLength > 0L && committedEnd >= contentLength) contentLength else committedEnd
    }

    private fun contiguousEnd(): Long {
        var end = 0L
        for (span in spans) {
            if (span.position > end) break
            end = maxOf(end, span.end)
        }
        return if (end > 0L) end else -1L
    }

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
            val reader = open(span.file) ?: break
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

    private fun open(file: File): RandomAccessFile? {
        readers[file]?.let { return it }
        if (!file.exists()) return null
        val reader = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
        readers[file] = reader
        return reader
    }

    @Synchronized
    override fun close() {
        readers.values.forEach { runCatching { it.close() } }
        readers.clear()
    }
}
