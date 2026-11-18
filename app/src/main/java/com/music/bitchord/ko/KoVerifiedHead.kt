package com.music.bitchord.ko

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * [KO] UPG-7: the head the sound check downloaded, kept for the swap that follows.
 *
 * 11-18 I Just Might: the check read 62.9 MB of a 24/96 FLAC in 115 s, deleted it,
 * and the swap then fetched all 81 MB again from 0 on the same URL. The song ended
 * 80 s later with the second download still running, so no swap.
 *
 * Only a head that the check ACCEPTED, from the same URL, source and row the swap
 * serves, is ever handed over. Its bytes go into the swap's empty cache entry as
 * one span (moved, not copied, when the folders share a disk), and the rest is
 * fetched with a Range by the same cache writer as before. The server states the
 * file's length again on that read; a length other than the one the check saw is
 * another file, and the entry is thrown away and fetched whole (the old path).
 *
 * Nothing here polls: heads are dropped when a check ends unaccepted, when the
 * swap takes them, when a newer head replaces them, and when the track changes.
 */
internal object KoVerifiedHead {

    /** A smaller head saves too little to be worth holding on disk. */
    const val MIN_SEED_BYTES = 1L * 1024 * 1024

    /**
     * Left for the network even when the check held the whole file, so the rest
     * is always a real ranged read that states the file's length (the splice
     * check) and records who served the entry, as every other fill does.
     */
    const val TAIL_BYTES = 64L * 1024

    /** The current song and the next one; anything older is deleted. */
    private const val MAX_HELD = 2

    private const val PREFIX = "held-"

    class Entry internal constructor(
        val file: File,
        val url: String,
        val sourceConfigId: String?,
        val trackId: String?,
        val bytes: Long,
        val totalBytes: Long,
    ) {
        @Volatile
        internal var accepted: Boolean = false

        /** The YouTube id the check accepted this head for; null when not said. */
        @Volatile
        internal var youtubeId: String? = null
    }

    /** Keyed by URL. Guarded by `this`. */
    private val held = LinkedHashMap<String, Entry>()

    /**
     * The length of the whole file a head response is the start of, or -1 when
     * the response does not say so safely (no length, a compressed body, a range
     * that does not start at 0).
     */
    fun totalOf(code: Int, contentLength: Long, contentRange: String?, contentEncoding: String?): Long {
        if (!contentEncoding.isNullOrBlank() && !contentEncoding.trim().equals("identity", ignoreCase = true)) {
            return -1L
        }
        return when (code) {
            200 -> if (contentRange.isNullOrBlank() && contentLength > 0L) contentLength else -1L
            206 -> rangeTotal(contentRange)
            else -> -1L
        }
    }

    private val RANGE = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+)\s*$""", RegexOption.IGNORE_CASE)

    private fun rangeTotal(header: String?): Long {
        val match = RANGE.matchEntire(header ?: return -1L) ?: return -1L
        val start = match.groupValues[1].toLongOrNull() ?: return -1L
        val total = match.groupValues[3].toLongOrNull() ?: return -1L
        return if (start == 0L && total > 0L) total else -1L
    }

    /** Whether a head of [bytes] from a file of [total] is worth keeping. */
    fun worthHolding(bytes: Long, total: Long, url: String): Boolean =
        url.isNotBlank() && total > 0L && bytes in MIN_SEED_BYTES..total

    /**
     * Takes [file], a head of [url] whose whole file is [total] bytes, out of the
     * check's way (renamed in the same folder). Null when it is not kept; the
     * caller's own delete then removes it as before.
     */
    fun offer(file: File, url: String, sourceConfigId: String?, trackId: String?, total: Long): Entry? {
        val bytes = file.length()
        if (!file.isFile || !worthHolding(bytes, total, url)) return null
        val folder = file.parentFile ?: return null
        val kept = File(folder, "$PREFIX${UUID.randomUUID()}.bin")
        // Under the lock, so a sweep beside it never sees a renamed file not yet listed.
        val (entry, dropped) = synchronized(this) {
            if (!file.renameTo(kept)) return null
            val made = Entry(kept, url, sourceConfigId, trackId, bytes, total)
            val out = mutableListOf<File>()
            held.remove(url)?.let { out += it.file }
            held[url] = made
            while (held.size > MAX_HELD) {
                val oldest = held.keys.first()
                held.remove(oldest)?.let { out += it.file }
            }
            // Heads left by a process that died before it could delete them.
            val live = held.values.map { it.file.name }.toSet()
            folder.listFiles()
                ?.filter { it.name.startsWith(PREFIX) && it.name !in live }
                ?.let(out::addAll)
            made to out
        }
        dropped.forEach { it.delete() }
        return entry
    }

    /** The check accepted the candidate [entry] holds; only now may a swap take it. */
    fun accept(entry: Entry, youtubeId: String? = null): Boolean = synchronized(this) {
        if (held[entry.url] !== entry) return false
        entry.youtubeId = youtubeId
        entry.accepted = true
        true
    }

    /** The check of [url] ended; a head it did not accept is deleted. */
    fun dropPending(url: String) {
        val dropped = synchronized(this) {
            val entry = held[url]?.takeIf { !it.accepted } ?: return
            held.remove(url)
            entry
        }
        dropped.file.delete()
    }

    /**
     * The accepted head of exactly this candidate (same URL, source and row), now
     * owned by the caller, who must delete its file. Null otherwise; a head of
     * another row under the same URL stays where it is.
     */
    fun take(url: String, sourceConfigId: String?, trackId: String?): Entry? = synchronized(this) {
        val entry = held[url] ?: return null
        if (!entry.accepted || entry.sourceConfigId != sourceConfigId || entry.trackId != trackId) return null
        held.remove(url)
        entry
    }

    fun heldCount(): Int = synchronized(this) { held.size }

    /**
     * dupdl: whether an accepted head of exactly this stream is held, asked
     * before a player's open lays it in, so a head is never taken (and deleted)
     * by an open that cannot use it.
     */
    fun holdsAccepted(url: String, sourceConfigId: String?, trackId: String?): Boolean = synchronized(this) {
        val entry = held[url] ?: return false
        entry.accepted && entry.sourceConfigId == sourceConfigId && entry.trackId == trackId
    }

    /**
     * dupdl: a head laid in by a player's open has no cache writer of its own to
     * check the file's length on the ranged read that follows; the player's
     * fill states it in the entry instead. Keyed by cache key: the URL the head
     * came from and the whole file's length the check saw.
     */
    private val promised = LinkedHashMap<String, Pair<String, Long>>()

    private const val MAX_PROMISED = 4

    fun promise(key: String, url: String, total: Long) {
        synchronized(this) {
            promised.remove(key)
            promised[key] = url to total
            while (promised.size > MAX_PROMISED) promised.remove(promised.keys.first())
        }
    }

    fun hasPromises(): Boolean = synchronized(this) { promised.isNotEmpty() }

    /** The length promised for [key] when it is still served from [url]; -1 otherwise. */
    fun promisedLength(key: String, url: String?): Long = synchronized(this) {
        promised[key]?.takeIf { it.first == url }?.second ?: -1L
    }

    enum class Splice { NONE, AGREES, DIFFERS }

    /**
     * The entry [key], served from [url], now states [stated] bytes. AGREES
     * settles the promise; DIFFERS keeps it until [forgetPromise] (the caller
     * drops the entry); NONE while nothing is stated yet. A promise made for
     * another URL is stale (the entry was refilled) and is dropped.
     */
    fun settle(key: String, url: String?, stated: Long): Splice = synchronized(this) {
        val (promisedUrl, total) = promised[key] ?: return Splice.NONE
        if (promisedUrl != url) {
            promised.remove(key)
            return Splice.NONE
        }
        if (stated <= 0L) return Splice.NONE
        if (stated == total) {
            promised.remove(key)
            Splice.AGREES
        } else {
            Splice.DIFFERS
        }
    }

    fun forgetPromise(key: String) {
        synchronized(this) { promised.remove(key) }
    }

    /**
     * The track changed: no swap of the old one will ask any more. Detached at
     * once (a head the new track offers later is not caught); the caller deletes
     * the returned files off the main thread.
     *
     * [starting]: the ids of the track now starting. A head accepted for one of
     * them was proved ahead for this very song (the direct lead or the window's
     * proof) and is its swap's to take, so it stays; so does a head whose check
     * is still running, which its own accept or [dropPending] settles. Null
     * detaches everything.
     */
    fun detachAll(starting: Set<String>? = null): List<File> = synchronized(this) {
        val stale = held.values.filter { starting == null || (it.accepted && it.youtubeId !in starting) }
        stale.forEach { held.remove(it.url) }
        stale.map { it.file }
    }

    /** What to lay into the cache entry; [length] 0 means nothing, for [why]. */
    data class Plan(val length: Long, val why: String)

    /**
     * @param fileLength what the held file holds now (it lives in a folder the
     *   system may empty).
     * @param cachedBytes bytes the target entry already holds.
     * @param cachedLength the length the target entry already states (-1: none).
     */
    fun plan(heldBytes: Long, fileLength: Long, total: Long, cachedBytes: Long, cachedLength: Long): Plan {
        if (fileLength != heldBytes) return Plan(0L, "file changed")
        if (total <= 0L || heldBytes > total) return Plan(0L, "no length")
        // Never splice onto bytes of unknown origin, and never start under a stated
        // length: the server's own statement on the ranged read is the check.
        if (cachedBytes != 0L || cachedLength > 0L) return Plan(0L, "entry not empty")
        val length = minOf(heldBytes, total - TAIL_BYTES)
        if (length < MIN_SEED_BYTES) return Plan(0L, "too short")
        return Plan(length, if (heldBytes >= total) "whole file" else "head")
    }

    /** The whole-file fill matches the file the check saw; -1 expected means nothing to check. */
    fun lengthAgrees(expected: Long, actual: Long): Boolean = expected <= 0L || actual == expected

    /**
     * Puts the first [length] bytes of [source] at [target]: a rename when both
     * sit on one disk (no second copy), otherwise a bounded copy.
     */
    fun moveInto(source: File, target: File, length: Long): Boolean {
        if (source.length() < length) return false
        if (source.length() > length) RandomAccessFile(source, "rw").use { it.setLength(length) }
        if (source.renameTo(target)) return target.length() == length
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
            }
        }
        return target.length() == length
    }
}
