package com.music.bitchord.download

import org.junit.Assert.assertEquals
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
}
