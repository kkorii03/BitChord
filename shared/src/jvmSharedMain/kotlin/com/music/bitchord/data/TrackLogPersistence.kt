package com.music.bitchord.data

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Three files of this size hold about five megabytes together — sized to keep
 * at least a day of ordinary play in the export while still being a file
 * someone can attach to a report.
 */
private const val DEFAULT_MAX_BYTES = 1_700_000L

/**
 * The on-disk half of [TrackLog]: a small set of rotating text files that
 * outlive the process.
 *
 * The in-memory deque in [TrackLog] is fast and is what the per-track view
 * reads, but a crash or a process death takes it with it — exactly the moments
 * a diagnostic log is most wanted. This writes the same lines through a single
 * background thread, so a caller never waits on disk, and keeps the last few
 * megabytes (`trail-2.txt` oldest through `trail-0.txt` newest) for the export.
 *
 * Appending is event-driven: the writer thread blocks on a queue rather than
 * polling, so an idle app costs nothing. [appendSync] exists for the one caller
 * that cannot wait — the uncaught-exception handler, which has milliseconds
 * before the process goes away.
 *
 * Pure JVM on purpose: the rotation rules are tested without a device.
 */
class TrackLogPersistence(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val fileCount: Int = 3,
) {
    private val queue = LinkedBlockingQueue<Any>()
    private val lock = Any()

    private var writer: BufferedWriter? = null
    private val writerThread =
        Thread({ drain() }, "bitchord-trail").apply { isDaemon = true }

    @Volatile
    private var running = true

    init {
        require(fileCount > 0) { "fileCount must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        directory.mkdirs()
        writerThread.start()
    }

    /** Queues a line; the writer thread flushes it in a small batch. */
    fun append(line: String) {
        if (!running) return
        queue.offer(line)
    }

    /**
     * Writes a line on the calling thread and flushes it. Used by the crash
     * handler, where waiting for the writer thread would be waiting too long.
     */
    fun appendSync(line: String) {
        synchronized(lock) {
            writeLocked(line)
            writer?.flush()
        }
    }

    /**
     * Blocks until every queued line has reached the file.
     *
     * The signal is queued even when the queue looks empty: the writer may have
     * taken the last line but not reached the disk yet, and flushing on this
     * side would then return before that line landed.
     */
    fun flush() {
        if (!running) {
            synchronized(lock) { runCatching { writer?.flush() } }
            return
        }
        val signal = FlushSignal()
        queue.offer(signal)
        try {
            signal.done.await(FLUSH_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) { runCatching { writer?.flush() } }
    }

    /**
     * Every file's contents, oldest first. Used by the diagnostic export so the
     * last hours survive a restart.
     */
    fun readOldestFirst(): List<String> {
        flush()
        val out = ArrayList<String>()
        synchronized(lock) {
            for (index in fileCount - 1 downTo 0) {
                val file = fileFor(index)
                if (!file.isFile) continue
                runCatching { file.readLines() }.getOrNull()?.let(out::addAll)
            }
        }
        return out
    }

    /** Stops the writer after flushing what it has. */
    fun close() {
        if (!running) return
        running = false
        queue.offer(STOP_MARKER)
        synchronized(lock) {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun drain() {
        var lastFlushAt = nowMs()
        var bufferedChars = 0
        while (true) {
            val first = try {
                // With nothing buffered there is nothing to flush, so the writer
                // blocks indefinitely (no idle wake-up). Once a line is waiting,
                // the wait is the coalescing window: lines that arrive inside it
                // are written before the next flush, and the interval cap keeps
                // a continuous stream from holding them longer.
                if (bufferedChars == 0) {
                    queue.take()
                } else {
                    queue.poll(
                        TrackLogFlushPolicy.waitMs(nowMs(), lastFlushAt),
                        TimeUnit.MILLISECONDS,
                    )
                }
            } catch (_: InterruptedException) {
                if (!running) break else continue
            }
            if (first == null) {
                if (bufferedChars > 0) {
                    flushWriter()
                    lastFlushAt = nowMs()
                    bufferedChars = 0
                }
                if (!running) break
                continue
            }
            if (first === STOP_MARKER) {
                flushWriter()
                return
            }
            if (first is FlushSignal) {
                flushWriter()
                first.done.countDown()
                lastFlushAt = nowMs()
                bufferedChars = 0
                continue
            }
            synchronized(lock) {
                bufferedChars = writeBatch(first as String, bufferedChars, lastFlushAt) { at ->
                    lastFlushAt = at
                }
                while (true) {
                    val next = queue.poll() ?: break
                    if (next === STOP_MARKER) {
                        runCatching { writer?.flush() }
                        return
                    }
                    if (next is FlushSignal) {
                        runCatching { writer?.flush() }
                        next.done.countDown()
                        lastFlushAt = nowMs()
                        bufferedChars = 0
                        continue
                    }
                    bufferedChars = writeBatch(next as String, bufferedChars, lastFlushAt) { at ->
                        lastFlushAt = at
                    }
                }
                if (TrackLogFlushPolicy.shouldFlush(nowMs(), lastFlushAt, bufferedChars)) {
                    runCatching { writer?.flush() }
                    lastFlushAt = nowMs()
                    bufferedChars = 0
                }
            }
            if (!running) break
        }
        flushWriter()
    }

    /**
     * Writes one line and returns the new buffered-character count, flushing
     * first when rotation has already put the old buffer on disk.
     */
    private fun writeBatch(
        line: String,
        bufferedChars: Int,
        lastFlushAt: Long,
        flushedAt: (Long) -> Unit,
    ): Int {
        var pending = bufferedChars
        if (writeLocked(line)) {
            // Rotation flushed and closed the previous writer, so nothing it
            // held is still waiting.
            pending = 0
            flushedAt(nowMs())
        }
        pending += line.length + 1
        if (TrackLogFlushPolicy.shouldFlush(nowMs(), lastFlushAt, pending)) {
            runCatching { writer?.flush() }
            flushedAt(nowMs())
            return 0
        }
        return pending
    }

    private fun flushWriter() {
        synchronized(lock) { runCatching { writer?.flush() } }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** @return true when this write rotated the file, which flushed the old buffer. */
    private fun writeLocked(line: String): Boolean {
        val current = fileFor(0)
        val rotated = current.length() >= maxBytes
        if (rotated) rotateLocked()
        val out = writer ?: BufferedWriter(
            OutputStreamWriter(FileOutputStream(fileFor(0), true), Charsets.UTF_8),
        ).also { writer = it }
        out.write(line)
        out.newLine()
        return rotated
    }

    private fun rotateLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        runCatching { fileFor(fileCount - 1).delete() }
        for (index in fileCount - 1 downTo 1) {
            val from = fileFor(index - 1)
            if (!from.isFile) continue
            runCatching { from.renameTo(fileFor(index)) }
        }
        runCatching { fileFor(0).delete() }
    }

    private fun fileFor(index: Int): File = File(directory, "trail-$index.txt")

    private companion object {
        val STOP_MARKER = Any()
        const val FLUSH_WAIT_MS = 2_000L

        /** A flush request that wakes exactly the caller that made it. */
        class FlushSignal {
            val done = CountDownLatch(1)
        }
    }
}

/**
 * When the trail writer may flush.
 *
 * A flush is a write syscall, and the diagnostic log is written by whichever
 * playback path happens to be logging. Flushing per line made a quiet stream
 * pay one write per line; waiting for a short quiet window turns a burst into
 * one write, and the interval/size bounds keep a continuous stream from holding
 * lines indefinitely. The content is unchanged; only the moment it reaches the
 * file moves, and an explicit [TrackLog.flush], a crash, a background transition
 * and a process exit still flush immediately.
 */
internal object TrackLogFlushPolicy {

    /** A stream that goes quiet for this long is flushed. */
    const val IDLE_MS = 1_500L

    /** Even a stream that never goes quiet is flushed at least this often. */
    const val MAX_INTERVAL_MS = 3_000L

    /** Or once this many characters are waiting, whichever comes first. */
    const val MAX_BUFFERED_CHARS = 64 * 1024

    fun shouldFlush(nowMs: Long, lastFlushMs: Long, bufferedChars: Int): Boolean =
        bufferedChars >= MAX_BUFFERED_CHARS || nowMs - lastFlushMs >= MAX_INTERVAL_MS

    /**
     * How long the writer may wait for another line before flushing what it
     * has: the quiet window, cut short by whatever remains of the interval cap.
     */
    fun waitMs(nowMs: Long, lastFlushMs: Long): Long {
        val untilIntervalCap = MAX_INTERVAL_MS - (nowMs - lastFlushMs)
        return untilIntervalCap.coerceIn(1L, IDLE_MS)
    }
}

/**
 * The lines that crowd a useful log out and are never the answer themselves.
 *
 * PoToken's browser prints a parser's worth of console output per playback
 * start, and read-ahead brackets every probe with a pair of lines. Both are
 * replaced by one summary line at their source (see `PoTokenGenerator` and
 * `AudioCache`); this is the backstop for a line that still arrives.
 *
 * Warnings and errors are always kept: a failed mint or a fetch that was not
 * kept is exactly what a reader is looking for.
 */
object TrackLogNoise {
    fun keep(level: Char, tag: String, message: String): Boolean {
        if (level != 'D') return true
        if (tag == "PoTokenWebView" && message.startsWith("JS:")) return false
        if (message.startsWith("read-ahead fetching ")) return false
        if (message.startsWith("read-ahead fetched ")) return false
        return true
    }
}
