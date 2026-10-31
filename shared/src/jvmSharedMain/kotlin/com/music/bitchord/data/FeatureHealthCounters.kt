package com.music.bitchord.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Names for the numbers the app keeps about itself, one per criterion the
 * diagnostic reader already scores. The names are stable because an exported
 * log carries them out of the phone.
 */
object FeatureHealthCounterKeys {
    const val D1_ATTEMPTS = "D1-next-prep.attempts"
    const val D1_LYRICS = "D1-lyrics.successes"
    const val D1_HQ = "D1-hq.successes"
    const val D1_SYNC_ATTEMPTS = "D1-sync.attempts"
    const val D1_SYNC_SUCCESS = "D1-sync.successes"
    const val D1_SYNC_SKIP = "D1-sync-skip.attempts"
    const val D1_SYNC_SKIP_REASON = "D1-sync-skip.reason."
    const val D1_MIX = "D1-mix.successes"

    const val D2_VERIFY = "D2-playback.verifications"
    const val D2_FLAC = "D2-playback.flac"

    const val D3_ATTEMPTS = "D3-refill.attempts"
    const val D3_SUCCESS = "D3-refill.successes"

    const val D4_ATTEMPTS = "D4-video.attempts"
    const val D4_SUCCESS = "D4-video.successes"

    const val D5_ATTEMPTS = "D5-version.attempts"
    const val D5_SWAPS = "D5-version.successes"

    const val D6_ATTEMPTS = "D6-lyrics.attempts"
    const val D6_LYRICS = "D6-lyrics.successes"
    const val D6_SYNC_ATTEMPTS = "D6-sync.attempts"
    const val D6_SYNC_SUCCESS = "D6-sync.successes"

    const val D7_START = "D7-crossfade.attempts"
    const val D7_END = "D7-crossfade.successes"
    const val D7_ABORT = "D7-abort.attempts"

    const val D8_FIRST_ATTEMPTS = "D8-firstaudio.attempts"
    const val D8_FIRST_SUCCESS = "D8-firstaudio.successes"
    const val D8_FAILED_START = "D8-failed-start.attempts"

    const val D9_DOWNLOAD = "D9-download.failures"
    const val D9_HQ_LIBRARY = "D9-hq-library.failures"

    const val D10_ATTEMPTS = "D10-lifecycle.attempts"
    const val D10_SUCCESS = "D10-lifecycle.successes"
}

/**
 * The app's own 24-hour tally of the features the diagnostic reader scores.
 *
 * Only numbers are kept — never a title, an account or a URL. Events are
 * grouped per millisecond in memory and written as a small daily text file by
 * a background thread, so a process restart keeps the last day without a poll
 * or a timer. Writes are coalesced: a burst becomes one snapshot, and an idle
 * app writes nothing.
 *
 * The retention window is exact (an event at the cutoff stays, one a
 * millisecond older does not), which is why the in-memory map is keyed by the
 * event time rather than by an hour bucket.
 */
class FeatureHealthCounterStore(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val writeLock = Any()
    private val events = TreeMap<Long, MutableMap<String, Long>>()

    @Volatile
    private var directory: File? = null

    private var pending = 0
    private var lastWriteAt = 0L

    private val queue = LinkedBlockingQueue<Any>()

    @Volatile
    private var writer: Thread? = null

    @Volatile
    private var running = true

    /** Attaches the daily files. Idempotent, so a second call is free. */
    fun attach(directory: File) {
        val first = synchronized(lock) {
            if (this.directory != null) {
                false
            } else {
                directory.mkdirs()
                this.directory = directory
                loadLocked(clock())
                true
            }
        }
        if (first) startWriter()
    }

    /** Adds [amount] to [key] at the current time. */
    fun bump(key: String, amount: Long = 1L) {
        if (key.isBlank() || amount == 0L) return
        record(key, clock(), amount)
    }

    /** Adds [amount] to [key] at [atMs]; used by the observer and its tests. */
    fun record(key: String, atMs: Long, amount: Long = 1L) {
        if (key.isBlank() || amount == 0L) return
        var request = false
        synchronized(lock) {
            pruneLocked(clock())
            events.getOrPut(atMs) { HashMap() }
                .merge(key, amount, Long::plus)
            pending++
            val now = clock()
            if (directory != null &&
                (pending >= WRITE_BATCH || now - lastWriteAt >= WRITE_INTERVAL_MS)
            ) {
                pending = 0
                lastWriteAt = now
                request = true
            }
        }
        if (request) requestWrite()
    }

    /** Every key's count still inside the rolling window. */
    fun snapshot(nowMs: Long = clock()): Map<String, Long> {
        val cutoff = nowMs - windowMs
        val out = HashMap<String, Long>()
        synchronized(lock) {
            pruneLocked(nowMs)
            for ((_, counts) in events.tailMap(cutoff, true)) {
                for ((key, value) in counts) out.merge(key, value, Long::plus)
            }
        }
        return out
    }

    /**
     * The lines that go at the top of the diagnostic export, one per metric
     * with the same key the reader uses.
     */
    fun headerLines(nowMs: Long = clock()): List<String> {
        val counts = snapshot(nowMs)
        fun count(key: String): Long = counts[key] ?: 0L
        val lines = ArrayList<String>()
        fun metric(
            key: String,
            attempts: Long? = null,
            successes: Long? = null,
            failures: Long? = null,
            extra: String = "",
        ) {
            if (attempts == null && successes == null && failures == null) return
            val fields = ArrayList<String>()
            attempts?.let { fields += "attempts=$it" }
            successes?.let { fields += "successes=$it" }
            failures?.let { fields += "failures=$it" }
            if (extra.isNotBlank()) fields += extra
            lines += "feature-health: $key ${fields.joinToString(" ")}"
        }

        val d1 = count(FeatureHealthCounterKeys.D1_ATTEMPTS)
        metric("D1-next-prep", attempts = d1)
        metric("D1-lyrics", attempts = d1, successes = count(FeatureHealthCounterKeys.D1_LYRICS))
        metric("D1-hq", attempts = d1, successes = count(FeatureHealthCounterKeys.D1_HQ))
        metric(
            "D1-sync",
            attempts = count(FeatureHealthCounterKeys.D1_SYNC_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D1_SYNC_SUCCESS),
        )
        val skipReasons = counts.entries
            .filter { it.key.startsWith(FeatureHealthCounterKeys.D1_SYNC_SKIP_REASON) }
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(3)
            .joinToString(",") {
                it.key.removePrefix(FeatureHealthCounterKeys.D1_SYNC_SKIP_REASON) + "=" + it.value
            }
        metric(
            "D1-sync-skip",
            attempts = count(FeatureHealthCounterKeys.D1_SYNC_SKIP),
            extra = if (skipReasons.isBlank()) "" else "reasons=$skipReasons",
        )
        metric("D1-mix", attempts = d1, successes = count(FeatureHealthCounterKeys.D1_MIX))

        metric(
            "D2-playback",
            attempts = count(FeatureHealthCounterKeys.D2_VERIFY),
            successes = count(FeatureHealthCounterKeys.D2_FLAC),
        )

        val d3Attempts = count(FeatureHealthCounterKeys.D3_ATTEMPTS)
        val d3Successes = count(FeatureHealthCounterKeys.D3_SUCCESS)
        metric(
            "D3-refill",
            attempts = d3Attempts,
            successes = d3Successes,
            extra = "zero=${(d3Attempts - d3Successes).coerceAtLeast(0L)}",
        )

        metric(
            "D4-video",
            attempts = count(FeatureHealthCounterKeys.D4_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D4_SUCCESS),
        )

        metric(
            "D5-version",
            attempts = count(FeatureHealthCounterKeys.D5_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D5_SWAPS),
        )

        metric(
            "D6-lyrics",
            attempts = count(FeatureHealthCounterKeys.D6_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D6_LYRICS),
        )
        metric(
            "D6-sync",
            attempts = count(FeatureHealthCounterKeys.D6_SYNC_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D6_SYNC_SUCCESS),
        )

        metric(
            "D7-crossfade",
            attempts = count(FeatureHealthCounterKeys.D7_START),
            successes = count(FeatureHealthCounterKeys.D7_END),
        )
        metric(
            "D7-abort",
            attempts = count(FeatureHealthCounterKeys.D7_ABORT),
        )

        metric(
            "D8-firstaudio",
            attempts = count(FeatureHealthCounterKeys.D8_FIRST_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D8_FIRST_SUCCESS),
        )
        metric(
            "D8-failed-start",
            attempts = count(FeatureHealthCounterKeys.D8_FAILED_START),
        )

        metric("D9-download", failures = count(FeatureHealthCounterKeys.D9_DOWNLOAD))
        metric("D9-hq-library", failures = count(FeatureHealthCounterKeys.D9_HQ_LIBRARY))

        metric(
            "D10-lifecycle",
            attempts = count(FeatureHealthCounterKeys.D10_ATTEMPTS),
            successes = count(FeatureHealthCounterKeys.D10_SUCCESS),
        )
        return lines
    }

    /** Writes a snapshot now; used on background, before export, and by tests. */
    fun flush() {
        val target = directory ?: return
        val worker = writer
        if (worker == null) {
            writeSnapshot(target)
            return
        }
        val signal = FlushSignal()
        queue.offer(signal)
        runCatching { signal.done.await(FLUSH_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    /** Stops the writer after flushing; the app never needs this, tests do. */
    fun close() {
        running = false
        val worker = writer ?: return
        queue.offer(STOP)
        runCatching { worker.join(FLUSH_WAIT_MS) }
        writer = null
    }

    private fun startWriter() {
        synchronized(lock) {
            if (writer != null || !running) return
            writer = Thread({ drain() }, "bitchord-feature-health").apply { isDaemon = true }
            writer?.start()
        }
    }

    private fun requestWrite() {
        if (writer != null) queue.offer(WRITE)
    }

    private fun drain() {
        while (running) {
            val item = try {
                queue.take()
            } catch (_: InterruptedException) {
                continue
            }
            if (item === STOP) break
            val target = directory ?: continue
            writeSnapshot(target)
            if (item is FlushSignal) item.done.countDown()
        }
    }

    private fun writeSnapshot(target: File) {
        synchronized(writeLock) {
            val groups = HashMap<String, MutableList<String>>()
            val cutoff: Long
            synchronized(lock) {
                pruneLocked(clock())
                cutoff = clock() - windowMs
                for ((at, counts) in events.tailMap(cutoff, true)) {
                    val day = dayKey(at)
                    val lines = groups.getOrPut(day) { ArrayList() }
                    for ((key, value) in counts) {
                        lines += "$at $key $value"
                    }
                }
            }
            for ((day, lines) in groups) {
                val file = File(target, "$FILE_PREFIX$day.txt")
                val text = "# bitchord feature health v1\n" + lines.joinToString("\n") + "\n"
                val temp = File(target, file.name + ".tmp")
                runCatching {
                    temp.writeText(text)
                    if (!temp.renameTo(file)) {
                        file.writeText(text)
                        temp.delete()
                    }
                }
            }
            runCatching {
                val keepFrom = dayKey(cutoff)
                target.listFiles().orEmpty()
                    .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".txt") }
                    .filter { it.name.removePrefix(FILE_PREFIX).removeSuffix(".txt") < keepFrom }
                    .forEach { it.delete() }
            }
        }
    }

    private fun loadLocked(nowMs: Long) {
        val cutoff = nowMs - windowMs
        val files = directory?.listFiles().orEmpty()
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".txt") }
        for (file in files) {
            runCatching {
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank() || line.startsWith("#")) continue
                        val parts = line.split(' ')
                        if (parts.size < 3) continue
                        val at = parts[0].toLongOrNull() ?: continue
                        val amount = parts[2].toLongOrNull() ?: continue
                        if (at < cutoff) continue
                        events.getOrPut(at) { HashMap() }
                            .merge(parts[1], amount, Long::plus)
                    }
                }
            }
        }
        pruneLocked(nowMs)
    }

    private fun pruneLocked(nowMs: Long) {
        val cutoff = nowMs - windowMs
        val expired = events.headMap(cutoff, false).keys.toList()
        expired.forEach { events.remove(it) }
    }

    private fun dayKey(atMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(atMs))

    private companion object {
        const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L
        const val WRITE_BATCH = 16
        const val WRITE_INTERVAL_MS = 60_000L
        const val FLUSH_WAIT_MS = 2_000L
        const val FILE_PREFIX = "bitchord-feature-health-"

        val WRITE = Any()
        val STOP = Any()

        class FlushSignal {
            val done = CountDownLatch(1)
        }
    }
}

/**
 * Turns the lines the app already logs into the counters above. No line is
 * rewritten and no title is kept: the reader takes only the numbers it needs.
 */
class FeatureHealthObserver(
    private val bump: (String, Long) -> Unit,
) {
    private var processStartAt = 0L

    fun observe(tag: String, message: String, atMs: Long) {
        when {
            message.startsWith("next-prep: ") -> nextPrep(message)
            message.startsWith("hq-verify: ") -> {
                if (message.contains(" -> accepted")) bump(FeatureHealthCounterKeys.D2_VERIFY, 1L)
            }
            tag.equals("DECODE", ignoreCase = true) && message.contains("audio/flac") ->
                bump(FeatureHealthCounterKeys.D2_FLAC, 1L)
            message.startsWith("hq-swap: ") && message.contains(" done") ->
                bump(FeatureHealthCounterKeys.D2_FLAC, 1L)
            message.startsWith("hq-ahead: ") && message.contains("FLAC") ->
                bump(FeatureHealthCounterKeys.D2_FLAC, 1L)
            message.contains("is already playing") && message.contains("audio/flac") ->
                bump(FeatureHealthCounterKeys.D2_FLAC, 1L)
            message.startsWith("queue: autoplay-append ") -> {
                val n = token(message, "n")?.toLongOrNull() ?: 0L
                bump(FeatureHealthCounterKeys.D3_ATTEMPTS, 1L)
                if (n > 0L) bump(FeatureHealthCounterKeys.D3_SUCCESS, 1L)
            }
            message.startsWith("autoplay: empty") && message.contains("retry=exhausted") ->
                bump(FeatureHealthCounterKeys.D3_ATTEMPTS, 1L)
            message.startsWith("autoplay: nothing new") ->
                bump(FeatureHealthCounterKeys.D3_ATTEMPTS, 1L)
            message.startsWith("video-radio: ") && token(message, "mode") == "video" -> {
                val picked = token(message, "picked")?.toLongOrNull() ?: 0L
                bump(FeatureHealthCounterKeys.D4_ATTEMPTS, 1L)
                if (picked > 0L) bump(FeatureHealthCounterKeys.D4_SUCCESS, 1L)
            }
            message.startsWith("song version: ") -> songVersion(message)
            message.startsWith("track-summary: ") -> trackSummary(message)
            message.startsWith("crossfade: start ") -> bump(FeatureHealthCounterKeys.D7_START, 1L)
            message.startsWith("crossfade: end ") -> bump(FeatureHealthCounterKeys.D7_END, 1L)
            message.startsWith("crossfade: abort") -> bump(FeatureHealthCounterKeys.D7_ABORT, 1L)
            message.contains("failed to start after") ->
                bump(FeatureHealthCounterKeys.D8_FAILED_START, 1L)
            message.startsWith("download failed for ") ->
                bump(FeatureHealthCounterKeys.D9_DOWNLOAD, 1L)
            message.startsWith("hq-library: ") && message.contains(" did not land") ->
                bump(FeatureHealthCounterKeys.D9_HQ_LIBRARY, 1L)
            message.startsWith("lifecycle: process start") -> {
                processStartAt = atMs
                bump(FeatureHealthCounterKeys.D10_ATTEMPTS, 1L)
            }
            message.startsWith("lifecycle: foreground") -> {
                if (processStartAt > 0L && atMs - processStartAt <= FOREGROUND_WINDOW_MS) {
                    bump(FeatureHealthCounterKeys.D10_SUCCESS, 1L)
                    processStartAt = 0L
                }
            }
        }
    }

    private fun nextPrep(message: String) {
        val lyrics = token(message, "lyrics") ?: return
        val hq = token(message, "hq") ?: "none"
        val sync = token(message, "sync") ?: "none"
        val mix = token(message, "mix") ?: "none"
        bump(FeatureHealthCounterKeys.D1_ATTEMPTS, 1L)
        if (!isNone(lyrics)) bump(FeatureHealthCounterKeys.D1_LYRICS, 1L)
        if (!isNone(hq)) bump(FeatureHealthCounterKeys.D1_HQ, 1L)
        if (mix == "ready") bump(FeatureHealthCounterKeys.D1_MIX, 1L)
        if (!isNone(lyrics)) {
            bump(FeatureHealthCounterKeys.D1_SYNC_ATTEMPTS, 1L)
            if (sync.startsWith("applied")) {
                bump(FeatureHealthCounterKeys.D1_SYNC_SUCCESS, 1L)
            }
        }
        if (!sync.startsWith("applied") && !isNone(lyrics)) {
            val reason = sync.removePrefix("skipped(").removeSuffix(")")
                .ifBlank { "skipped" }
                .replace(' ', '_')
            bump(FeatureHealthCounterKeys.D1_SYNC_SKIP, 1L)
            bump(FeatureHealthCounterKeys.D1_SYNC_SKIP_REASON + reason, 1L)
        }
    }

    private fun songVersion(message: String) {
        val body = message.removePrefix("song version: ")
        if (body.contains(" kept (")) {
            bump(FeatureHealthCounterKeys.D5_ATTEMPTS, 1L)
            return
        }
        val arrow = body.indexOf(" -> ")
        if (arrow < 0) return
        bump(FeatureHealthCounterKeys.D5_ATTEMPTS, 1L)
        val destination = body.substring(arrow + 4).substringBefore(' ')
        if (!isNone(destination)) bump(FeatureHealthCounterKeys.D5_SWAPS, 1L)
    }

    private fun trackSummary(message: String) {
        val lyrics = token(message, "lyrics") ?: "none"
        val sync = token(message, "sync") ?: "none"
        bump(FeatureHealthCounterKeys.D6_ATTEMPTS, 1L)
        if (!isNone(lyrics)) {
            bump(FeatureHealthCounterKeys.D6_LYRICS, 1L)
            bump(FeatureHealthCounterKeys.D6_SYNC_ATTEMPTS, 1L)
            if (!isNone(sync)) bump(FeatureHealthCounterKeys.D6_SYNC_SUCCESS, 1L)
        }
        val firstAudio = token(message, "firstAudio")
        val firstAudioMs = firstAudio?.toLongOrNull()
        if (firstAudioMs != null) {
            bump(FeatureHealthCounterKeys.D8_FIRST_ATTEMPTS, 1L)
            if (firstAudioMs <= FIRST_AUDIO_LIMIT_MS) {
                bump(FeatureHealthCounterKeys.D8_FIRST_SUCCESS, 1L)
            }
        }
    }

    private fun isNone(value: String): Boolean =
        value == "none" || value.startsWith("none(")

    private fun token(message: String, name: String): String? {
        val marker = "$name="
        var index = message.indexOf(marker)
        while (index >= 0) {
            if (index == 0 || message[index - 1].isWhitespace()) {
                val start = index + marker.length
                var end = start
                while (end < message.length && !message[end].isWhitespace()) end++
                return message.substring(start, end)
            }
            index = message.indexOf(marker, index + 1)
        }
        return null
    }

    private companion object {
        const val FOREGROUND_WINDOW_MS = 10_000L
        const val FIRST_AUDIO_LIMIT_MS = 3_000L
    }
}

/** The app-wide counters, wired into [TrackLog] and the diagnostic export. */
object FeatureHealthCounters {
    private val store = FeatureHealthCounterStore()
    private val observer = FeatureHealthObserver { key, amount -> store.bump(key, amount) }

    fun init(directory: File) = store.attach(directory)

    fun observe(tag: String, message: String) {
        observer.observe(tag, message, System.currentTimeMillis())
    }

    fun bump(key: String, amount: Long = 1L) = store.bump(key, amount)

    fun headerLines(nowMs: Long = System.currentTimeMillis()): List<String> =
        store.headerLines(nowMs)

    fun flush() = store.flush()
}
