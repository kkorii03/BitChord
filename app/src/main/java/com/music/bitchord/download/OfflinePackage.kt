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
     * Build a package in a new directory, keeping any earlier one intact.
     *
     * The body owns [root] only. If it throws — a dropped segment, a cancelled
     * coroutine — this deletes that new directory and rethrows; nothing outside
     * it is touched.
     */
    fun <T> writeNew(parent: File, id: String, body: (File) -> T): T {
        val root = newDirectory(parent, id)
        return try {
            body(root)
        } catch (e: Throwable) {
            root.deleteRecursively()
            throw e
        }
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
