package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An offline package is replaced only after the new one is complete. The old
 * save deleted the directory before fetching the first segment, so a dropped
 * connection left the record pointing at a package that was already gone.
 */
class OfflinePackageTest {

    @Test
    fun `a failed save leaves the previous package untouched`() {
        val parent = Files.createTempDirectory("offline-hls").toFile()
        try {
            val old = File(parent, "old").apply { mkdirs() }
            File(old, "playlist.m3u8").writeText("old package")

            val failure = runCatching {
                OfflinePackage.writeNew(parent, "video-id") { root ->
                    File(root, "playlist.m3u8").writeText("new package")
                    error("network went away")
                }
            }.exceptionOrNull()

            assertEquals("network went away", failure?.message)
            assertEquals("old package", File(old, "playlist.m3u8").readText())
            assertEquals(listOf("old"), parent.list()?.toList())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `each successful save is written in a fresh directory`() {
        val parent = Files.createTempDirectory("offline-hls").toFile()
        try {
            val first = OfflinePackage.writeNew(parent, "same-id") { root ->
                File(root, "playlist.m3u8").writeText("first")
                root
            }
            val second = OfflinePackage.writeNew(parent, "same-id") { root ->
                File(root, "playlist.m3u8").writeText("second")
                root
            }

            assertNotEquals(first, second)
            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)
            assertEquals("first", File(first, "playlist.m3u8").readText())
            assertEquals("second", File(second, "playlist.m3u8").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `an old unreferenced package is swept but referenced and new ones stay`() {
        val parent = Files.createTempDirectory("offline-hls").toFile()
        try {
            val cutoff = 2_000L
            val stale = File(parent, "stale").apply {
                mkdirs()
                File(this, "segment-00000.m4s").writeText("half")
                setLastModified(cutoff - 1_000L)
            }
            val referenced = File(parent, "referenced").apply {
                mkdirs()
                File(this, "playlist.m3u8").writeText("whole")
                setLastModified(cutoff - 1_000L)
            }
            val fresh = File(parent, "fresh").apply {
                mkdirs()
                setLastModified(cutoff + 1_000L)
            }

            assertEquals(
                1,
                OfflinePackage.sweepUnreferenced(
                    parent = parent,
                    referencedRoots = setOf(referenced.absolutePath),
                    olderThanMs = cutoff,
                ),
            )

            assertFalse(stale.exists())
            assertTrue(referenced.exists())
            assertTrue(fresh.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `a package being written is not swept`() {
        val parent = Files.createTempDirectory("offline-hls").toFile()
        try {
            OfflinePackage.writeNew(parent, "in-flight") { root ->
                root.setLastModified(1_000L)
                assertEquals(
                    0,
                    OfflinePackage.sweepUnreferenced(
                        parent = parent,
                        referencedRoots = emptySet(),
                        olderThanMs = 2_000L,
                    ),
                )
                assertTrue(root.exists())
                File(root, "playlist.m3u8").writeText("whole")
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
