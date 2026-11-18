package com.music.bitchord.playback.smart

import com.music.bitchord.ko.KoVerifyHeadUse
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPG-speed item 8, first half: the timing line says how much of a verify head
 * the decoder actually read, so the head can be sized from real reads later.
 */
class HqVerifyHeadUseTest {

    @Test
    fun `the growing reader remembers the furthest byte read, not the last`() {
        val root = File(System.getProperty("java.io.tmpdir"), "bitchord-headuse-${System.nanoTime()}").apply { mkdirs() }
        try {
            val file = File(root, "candidate.bin").apply { writeBytes(ByteArray(100) { it.toByte() }) }
            val progress = DownloadProgress().also { it.markFinished() }
            val reader = GrowingFileReader(file, expectedSize = 100L, progress = progress, pollMs = 5L)
            try {
                assertEquals(0L, reader.maxReadEnd)
                reader.readAt(60L, ByteArray(30), 0, 30)
                assertEquals(90L, reader.maxReadEnd)
                // An extractor seeking back to the start does not lower the mark.
                reader.readAt(0L, ByteArray(10), 0, 10)
                assertEquals(90L, reader.maxReadEnd)
                // A read past the end is EOF and reads nothing.
                assertEquals(-1, reader.readAt(100L, ByteArray(4), 0, 4))
                assertEquals(90L, reader.maxReadEnd)
            } finally {
                reader.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the suffix prints both figures, or a dash when unmeasured`() {
        assertEquals(" maxRead=123456 audio=91000ms", KoVerifyHeadUse.suffix(123_456L, 91_000L))
        assertEquals(" maxRead=- audio=60500ms", KoVerifyHeadUse.suffix(-1L, 60_500L))
        assertEquals(" maxRead=- audio=-", KoVerifyHeadUse.suffix(-1L, -1L))
    }

    @Test
    fun `the timing line carries the decoder's reads`() {
        val root = File(".").canonicalFile
        val source = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/HqAudioVerify.kt") }
            .first { it.exists() }
            .readText()
            .replace("\r\n", "\n")
        val line = source.substringAfter("private fun logTiming(").substringBefore("private fun reject(")
        assertTrue(line.contains("KoVerifyHeadUse.suffix(candidate.maxRead, candidate.audioMs)"))
        val growing = source.substringAfter("private fun featuresOfGrowing(").substringBefore("private fun featuresFrom(")
        assertTrue(growing.contains("timing?.maxReadEnd = reader.maxReadEnd"))
        assertTrue(source.contains("maxRead = slot.maxRead,"))
    }
}
