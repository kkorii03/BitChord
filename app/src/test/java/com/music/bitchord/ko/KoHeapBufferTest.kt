package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * oom3: the audition player asked for a fixed 24 MiB of Java-heap sample
 * buffers whatever the heap held (11-19 OOMs at 250/256 MB). It now takes at
 * most a quarter of the free heap, never under 8 MiB. On the old code
 * PlaybackService passed AUDITION_BUFFER_BYTES straight through and the wiring
 * test fails.
 */
class KoHeapBufferTest {
    private val mib = 1024L * 1024
    private val wanted = 24 * 1024 * 1024
    private val floor = KoHeapBuffer.AUDITION_FLOOR_BYTES

    @Test
    fun `a roomy heap keeps the full audition buffer`() {
        assertEquals(wanted, KoHeapBuffer.bytes(wanted, floor, maxHeap = 256 * mib, usedHeap = 82 * mib))
        assertEquals(wanted, KoHeapBuffer.bytes(wanted, floor, maxHeap = 512 * mib, usedHeap = 300 * mib))
    }

    @Test
    fun `a tight heap gives a quarter of what is left`() {
        // 256 - 200 = 56 MB free -> 14 MiB.
        assertEquals((14 * mib).toInt(), KoHeapBuffer.bytes(wanted, floor, maxHeap = 256 * mib, usedHeap = 200 * mib))
    }

    @Test
    fun `a nearly full heap stops at the floor`() {
        assertEquals(8 * 1024 * 1024, floor)
        assertEquals(floor, KoHeapBuffer.bytes(wanted, floor, maxHeap = 256 * mib, usedHeap = 240 * mib))
        assertEquals(floor, KoHeapBuffer.bytes(wanted, floor, maxHeap = 256 * mib, usedHeap = 300 * mib))
    }

    @Test
    fun `a floor above the wish never grows it`() {
        assertEquals(4 * 1024 * 1024, KoHeapBuffer.bytes(4 * 1024 * 1024, floor, maxHeap = 256 * mib, usedHeap = 0))
    }

    @Test
    fun `the live heap reading stays inside the bounds`() {
        val bytes = KoHeapBuffer.auditionBytes(wanted)
        assertTrue(bytes in floor..wanted)
    }

    @Test
    fun `the audition is built with the heap-sized buffer and the main players are not`() {
        val root = File(".").canonicalFile
        val service = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt") }
            .first { it.exists() }
            .readText()
        val audition = service.substringAfter("private fun buildAuditionPlayer(").substringBefore("internal fun watchUpgrade(")
        assertTrue(audition.contains(".setTargetBufferBytes(com.music.bitchord.ko.KoHeapBuffer.auditionBytes(AUDITION_BUFFER_BYTES))"))
        assertTrue(!audition.contains(".setTargetBufferBytes(AUDITION_BUFFER_BYTES)"))
        val main = service.substringAfter("private fun farBufferingLoadControl()").substringBefore(".build()")
        assertTrue(main.contains(".setTargetBufferBytes(bufferCeilingBytes(AppSettings.meteredConnection.value))"))
    }
}
