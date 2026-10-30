package com.music.bitchord.download

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Where an offline HLS/DASH package is built before it replaces anything.
 *
 * The old save wrote into the one directory named after the track and deleted
 * it first, so a failed re-download lost the package that was still on disk
 * while the record kept pointing at it. Every save now gets a fresh directory:
 * the previous package is untouched until [Downloads.remember] records the new
 * playlist, and a failure only removes the half-written directory.
 */
internal object OfflinePackage {

    private val sequence = AtomicLong()

    /**
     * Directories a save is writing into right now, guarded by [activeLock].
     *
     * The sweep holds the same lock while it creates or removes a directory, so
     * a package cannot become active between the sweep's decision and its
     * delete.
     */
    private val activeLock = Any()
    private val activeRoots = HashSet<String>()

    /**
     * Build a package in a new directory, keeping any earlier one intact.
     *
     * The body owns [root] only. If it throws — a dropped segment, a cancelled
     * coroutine — this deletes that new directory and rethrows; nothing outside
     * it is touched.
     */
    fun <T> writeNew(parent: File, id: String, body: (File) -> T): T {
        val root = synchronized(activeLock) {
            newDirectory(parent, id).also { activeRoots.add(it.absolutePath) }
        }
        return try {
            body(root)
        } catch (e: Throwable) {
            root.deleteRecursively()
            throw e
        } finally {
            synchronized(activeLock) { activeRoots.remove(root.absolutePath) }
        }
    }

    /**
     * Removes package directories left behind by a process death.
     *
     * A save builds its package under a fresh directory and only writes
     * `playlist.m3u8` at the end, so a process killed mid-save leaves a
     * directory no record names. [referencedRoots] are the directories the
     * download records still point at; those are kept even when they look
     * incomplete. Only directories older than [olderThanMs] are removed, so a
     * package just completed by this process is never swept before its record
     * is written.
     */
    internal fun sweepUnreferenced(
        parent: File,
        referencedRoots: Set<String>,
        olderThanMs: Long,
    ): Int {
        val directories = parent.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        synchronized(activeLock) {
            for (directory in directories) {
                val path = directory.absolutePath
                if (path in activeRoots || path in referencedRoots) continue
                if (directory.lastModified() >= olderThanMs) continue
                if (directory.deleteRecursively()) removed++
            }
        }
        return removed
    }

    internal fun newDirectory(parent: File, id: String): File {
        if (!parent.exists() && !parent.mkdirs()) {
            error("Could not create offline package folder")
        }
        val root = File(parent, directoryName(id))
        if (!root.mkdirs()) error("Could not create offline package")
        return root
    }

    /**
     * A collision-resistant, always-unique directory name.
     *
     * The old name was the 32-bit `hashCode` of the video id, so two uploads
     * that collided shared a directory and one save could delete the other's
     * package. SHA-256 removes that; the counter makes two saves of the same id
     * in one process distinct even if they land in the same nanosecond.
     */
    internal fun directoryName(id: String): String =
        "${sha256(id).take(32)}-${System.nanoTime().toString(16)}-${sequence.incrementAndGet()}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}
