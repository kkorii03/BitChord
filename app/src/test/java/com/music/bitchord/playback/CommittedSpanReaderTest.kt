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
    fun `a partial entry's size is its contiguous committed end`() {
        // The metadata knows the whole rendition's length, but only the
        // opening is committed. Reporting the full length here used to let the
        // extractor seek into bytes that are not on disk, which is the
        // no-first-sample failure this reader exists to avoid.
        val file = tempFile("abcd".toByteArray())
        val reader = CommittedSpanReader(listOf(CommittedSpan(0, 4, file)), contentLength = 4_000)

        assertEquals(4L, reader.size())
        reader.close()
    }

    @Test
    fun `a known length with a hole does not make the size complete`() {
        val first = tempFile("abcd".toByteArray())
        val later = tempFile("ijkl".toByteArray())
        val reader = CommittedSpanReader(
            listOf(
                CommittedSpan(0, 4, first),
                CommittedSpan(8, 4, later),
            ),
            contentLength = 12,
        )

        assertEquals("only the contiguous opening is readable", 4L, reader.size())
        reader.close()
    }

    @Test
    fun `a rendition with no committed head has size -1`() {
        val later = tempFile("ijkl".toByteArray())
        val reader = CommittedSpanReader(listOf(CommittedSpan(8, 4, later)), contentLength = 12)

        assertEquals(-1L, reader.size())
        reader.close()
    }

    @Test
    fun `a span missing at construction is never advertised as size`() {
        // The snapshot can still name a span the evictor already removed.
        // Counting it made size() promise bytes read() could not return,
        // which is the no-first-sample failure this reader exists to avoid.
        val missing = tempFile("abcd".toByteArray()).apply { delete() }
        val present = tempFile("efgh".toByteArray())
        val reader = CommittedSpanReader(
            listOf(
                CommittedSpan(0, 4, missing),
                CommittedSpan(4, 4, present),
            ),
            contentLength = 8,
        )

        assertEquals(-1L, reader.size())
        assertEquals(-1, reader.read(0, ByteArray(8), 0, 8))
        assertEquals(4, reader.read(4, ByteArray(4), 0, 4))
        reader.close()
    }

    @Test
    fun `a span evicted after the snapshot is still readable`() {
        // The evictor may unlink a span after [AudioCache.renditionDataSource]
        // snapshots it. Opening the files with the reader keeps the decode off
        // that race; the old lazy open found the file already gone and the
        // decode stopped mid-track.
        val file = tempFile("abcdefgh".toByteArray())
        val reader = CommittedSpanReader(listOf(CommittedSpan(0, 8, file)), contentLength = 8)

        assertEquals("the evictor removed the cached file", true, file.delete())

        val out = ByteArray(8)
        assertEquals(8, reader.read(0, out, 0, out.size))
        assertEquals("abcdefgh", String(out))
        reader.close()
    }
}
