package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** oom1119: the per-track heap line, and a heap dump only when one is asked for. */
class KoHeapTrendTest {

    @Test
    fun `the heap line reads in megabytes`() {
        assertEquals(
            "heap: track=x java=201/256MB native=142MB",
            KoHeapTrend.line("track=x", 201L shl 20, 256L shl 20, (142L shl 20) + 5),
        )
    }

    @Test
    fun `no request, no dump`() {
        val dir = Files.createTempDirectory("ko-heap").toFile()
        try {
            assertNull(KoHeapTrend.dumpIfAsked(dir))
            assertEquals(0, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `one request is one dump`() {
        val dir = Files.createTempDirectory("ko-heap").toFile()
        try {
            File(dir, KoHeapTrend.REQUEST).writeText("")
            assertNotNull(KoHeapTrend.dumpIfAsked(dir))
            assertFalse(File(dir, KoHeapTrend.REQUEST).exists())
            assertNull(KoHeapTrend.dumpIfAsked(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
