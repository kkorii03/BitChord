package com.music.bitchord.playback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A background decode must read only committed cache spans, and must do it
 * without going back through SimpleCache. The old reader opened a
 * CacheDataSource per seek, which touched the span and stored the cache index
 * under SimpleCache's monitor; the player's next open then waited on it.
 */
class CommittedSpanReaderTest {

    private fun tempFile(bytes: ByteArray): File =
        Files.createTempFile("bitchord-span", ".bin").toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun `adjacent committed spans read as one file`() {
        val first = tempFile("abcd".toByteArray())
        val second = tempFile("efgh".toByteArray())
        val reader = CommittedSpanReader(
            listOf(
                CommittedSpan(0, 4, first),
                CommittedSpan(4, 4, second),
            ),
            contentLength = 8,
        )
        val out = ByteArray(8)

        assertEquals(8, reader.read(0, out, 0, out.size))
        assertEquals("abcdefgh", String(out))
        reader.close()
    }

    @Test
    fun `a hole reads as end of input instead of the next span`() {
        val first = tempFile("abcd".toByteArray())
        val later = tempFile("ijkl".toByteArray())
        val reader = CommittedSpanReader(
            listOf(
                CommittedSpan(0, 4, first),
                CommittedSpan(8, 4, later),
            ),
            contentLength = 12,
        )
        val out = ByteArray(12)

        assertEquals("reads only up to the hole", 4, reader.read(0, out, 0, out.size))
        assertEquals(-1, reader.read(4, out, 0, out.size))
        assertEquals("still reads a committed span after a seek", 4, reader.read(8, out, 0, out.size))
        reader.close()
    }

    @Test
    fun `bytes past a span's committed length are never handed out`() {
        // The file is longer than the committed span: the tail is still being
        // written, and a decoder must not see it as part of the finished file.
        val file = tempFile(ByteArray(100) { it.toByte() })
        val reader = CommittedSpanReader(listOf(CommittedSpan(0, 40, file)), contentLength = 0)

        val out = ByteArray(100)
        assertEquals(40, reader.read(0, out, 0, out.size))
        assertEquals(-1, reader.read(40, out, 0, out.size))
        reader.close()
    }

    @Test
    fun `size is the declared length, or the last committed byte when unknown`() {
        val file = tempFile("abcd".toByteArray())
        val known = CommittedSpanReader(listOf(CommittedSpan(0, 4, file)), contentLength = 4_000)
        val unknown = CommittedSpanReader(listOf(CommittedSpan(0, 4, file)), contentLength = 0)

        assertEquals(4_000L, known.size())
        assertEquals(4L, unknown.size())
        known.close()
        unknown.close()
    }
}
