package com.music.bitchord.data

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    private val maxBytes: Long = 1_048_576L,
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

    /** Blocks until every queued line has reached the file. */
    fun flush() {
        if (queue.isEmpty()) {
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
        while (true) {
            val first = try {
                queue.take()
            } catch (_: InterruptedException) {
                if (!running) break else continue
            }
            if (first === STOP_MARKER) break
            if (first is FlushSignal) {
                synchronized(lock) { runCatching { writer?.flush() } }
                first.done.countDown()
                continue
            }
            synchronized(lock) {
                writeLocked(first as String)
                // A burst of lines (a track start, a resolve) arrives together.
                // Coalesce it into one flush rather than one per line.
                while (true) {
                    val next = queue.poll() ?: break
                    if (next === STOP_MARKER) {
                        writer?.flush()
                        return
                    }
                    if (next is FlushSignal) {
                        writer?.flush()
                        next.done.countDown()
                        continue
                    }
                    writeLocked(next as String)
                }
                writer?.flush()
            }
            if (!running) break
        }
        synchronized(lock) {
            runCatching { writer?.flush() }
        }
    }

    private fun writeLocked(line: String) {
        val current = fileFor(0)
        if (current.length() >= maxBytes) rotateLocked()
        val out = writer ?: BufferedWriter(
            OutputStreamWriter(FileOutputStream(fileFor(0), true), Charsets.UTF_8),
        ).also { writer = it }
        out.write(line)
        out.newLine()
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
