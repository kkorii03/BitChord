package com.music.bitchord.ko

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UPG-7: 11-18 I Just Might (8_C6lXoGB5Y). The sound check held 62,899,200 bytes of
 * an 81,149,424-byte 24/96 FLAC, deleted them, and the swap fetched the whole file
 * again from 0; the song ended before the swap. The accepted head of the same
 * stream is now handed to the cache entry and only the rest is fetched.
 */
class KoVerifiedHeadTest {

    private lateinit var folder: File

    private val url = "https://flac.example/track/1?sig=a"
    private val total = 81_149_424L
    private val headBytes = 62_899_200L

    @Before
    fun setUp() {
        folder = Files.createTempDirectory("hq_verify").toFile()
        KoVerifiedHead.detachAll().forEach { it.delete() }
    }

    @After
    fun tearDown() {
        KoVerifiedHead.detachAll().forEach { it.delete() }
        folder.deleteRecursively()
    }

    private fun head(bytes: Long, name: String = "candidate-1.bin"): File =
        File(folder, name).also { file -> RandomAccessFile(file, "rw").use { it.setLength(bytes) } }

    @Test
    fun `the head response states the whole file's length only when it is safe`() {
        // Plain GET of the whole file.
        assertEquals(total, KoVerifiedHead.totalOf(200, total, null, null))
        // Ranged GET from 0 (a `clen` URL).
        assertEquals(total, KoVerifiedHead.totalOf(206, total, "bytes 0-81149423/81149424", null))
        assertEquals(total, KoVerifiedHead.totalOf(206, 1_048_576L, "bytes 0-1048575/81149424", "identity"))
        // A range that does not start at 0 is not a head.
        assertEquals(-1L, KoVerifiedHead.totalOf(206, 10L, "bytes 5-14/81149424", null))
        // Unknown total, no length, a compressed body, a 200 with a range.
        assertEquals(-1L, KoVerifiedHead.totalOf(206, 10L, "bytes 0-9/*", null))
        assertEquals(-1L, KoVerifiedHead.totalOf(200, -1L, null, null))
        assertEquals(-1L, KoVerifiedHead.totalOf(200, total, null, "gzip"))
        assertEquals(-1L, KoVerifiedHead.totalOf(200, total, "bytes 0-9/10", null))
        assertEquals(-1L, KoVerifiedHead.totalOf(304, total, null, null))
    }

    @Test
    fun `the accepted head of the same stream is taken by the swap`() {
        val entry = KoVerifiedHead.offer(head(headBytes), url, "src", "row-1", total)
        assertNotNull(entry)
        assertFalse("the verifier's own delete must not reach the kept file", File(folder, "candidate-1.bin").exists())
        assertTrue(entry!!.file.isFile)
        assertTrue(KoVerifiedHead.accept(entry))
        val taken = KoVerifiedHead.take(url, "src", "row-1")
        assertSame(entry, taken)
        assertEquals(headBytes, taken!!.bytes)
        assertEquals(total, taken.totalBytes)
        // Taken once: the second swap of the same URL starts from 0 as before.
        assertNull(KoVerifiedHead.take(url, "src", "row-1"))
    }

    @Test
    fun `a head the check did not accept is never served and is deleted`() {
        val entry = KoVerifiedHead.offer(head(headBytes), url, "src", "row-1", total)!!
        assertNull(KoVerifiedHead.take(url, "src", "row-1"))
        KoVerifiedHead.dropPending(url)
        assertFalse(entry.file.exists())
        assertFalse(KoVerifiedHead.accept(entry))
        assertNull(KoVerifiedHead.take(url, "src", "row-1"))
    }

    @Test
    fun `an accepted head survives the check's end but not the track change`() {
        val entry = KoVerifiedHead.offer(head(headBytes), url, "src", "row-1", total)!!
        KoVerifiedHead.accept(entry)
        KoVerifiedHead.dropPending(url)
        assertTrue(entry.file.exists())
        val stale = KoVerifiedHead.detachAll()
        assertEquals(listOf(entry.file), stale)
        assertNull(KoVerifiedHead.take(url, "src", "row-1"))
    }

    @Test
    fun `another url, source or row does not take the head`() {
        val entry = KoVerifiedHead.offer(head(headBytes), url, "src", "row-1", total)!!
        KoVerifiedHead.accept(entry)
        // A lighter tier or a fresh URL is another file.
        assertNull(KoVerifiedHead.take("https://flac.example/track/1?sig=b", "src", "row-1"))
        assertNull(KoVerifiedHead.take(url, "other", "row-1"))
        assertNull(KoVerifiedHead.take(url, "src", "row-2"))
        assertSame(entry, KoVerifiedHead.take(url, "src", "row-1"))
    }

    @Test
    fun `a head without a stated length or too small is not kept`() {
        val file = head(headBytes)
        assertNull(KoVerifiedHead.offer(file, url, "src", "row-1", -1L))
        assertTrue("left for the verifier's delete", file.exists())
        assertNull(KoVerifiedHead.offer(head(1024L, "small.bin"), url, "src", "row-1", total))
        // More bytes than the file claims is not a head of it.
        assertNull(KoVerifiedHead.offer(head(headBytes, "big.bin"), url, "src", "row-1", headBytes - 1))
        assertEquals(0, KoVerifiedHead.heldCount())
    }

    @Test
    fun `at most two heads are held and the oldest file is deleted`() {
        val first = KoVerifiedHead.offer(head(headBytes, "a.bin"), "$url&n=1", "src", "1", total)!!
        val second = KoVerifiedHead.offer(head(headBytes, "b.bin"), "$url&n=2", "src", "2", total)!!
        val third = KoVerifiedHead.offer(head(headBytes, "c.bin"), "$url&n=3", "src", "3", total)!!
        assertEquals(2, KoVerifiedHead.heldCount())
        assertFalse(first.file.exists())
        assertTrue(second.file.exists())
        assertTrue(third.file.exists())
    }

    @Test
    fun `a head left by a dead process is swept`() {
        val orphan = File(folder, "held-orphan.bin").also { it.writeBytes(ByteArray(8)) }
        KoVerifiedHead.offer(head(headBytes), url, "src", "row-1", total)!!
        assertFalse(orphan.exists())
    }

    @Test
    fun `the I Just Might head seeds its bytes and leaves the rest to a ranged read`() {
        val plan = KoVerifiedHead.plan(headBytes, headBytes, total, cachedBytes = 0L, cachedLength = -1L)
        assertEquals(headBytes, plan.length)
        assertEquals("head", plan.why)
    }

    @Test
    fun `a whole file held still leaves its tail to the network`() {
        val plan = KoVerifiedHead.plan(total, total, total, cachedBytes = 0L, cachedLength = -1L)
        assertEquals(total - KoVerifiedHead.TAIL_BYTES, plan.length)
        assertEquals("whole file", plan.why)
    }

    @Test
    fun `nothing is spliced onto an entry that already holds bytes or a length`() {
        assertEquals(0L, KoVerifiedHead.plan(headBytes, headBytes, total, cachedBytes = 4096L, cachedLength = -1L).length)
        assertEquals(0L, KoVerifiedHead.plan(headBytes, headBytes, total, cachedBytes = -1L, cachedLength = -1L).length)
        assertEquals(0L, KoVerifiedHead.plan(headBytes, headBytes, total, cachedBytes = 0L, cachedLength = total).length)
        // The system emptied the cache folder under the held file.
        assertEquals(0L, KoVerifiedHead.plan(headBytes, 0L, total, cachedBytes = 0L, cachedLength = -1L).length)
        assertEquals(0L, KoVerifiedHead.plan(headBytes, headBytes, -1L, cachedBytes = 0L, cachedLength = -1L).length)
    }

    @Test
    fun `a fill of another length than the verified file is not complete`() {
        assertTrue(KoVerifiedHead.lengthAgrees(total, total))
        assertFalse(KoVerifiedHead.lengthAgrees(total, total + 1))
        assertFalse(KoVerifiedHead.lengthAgrees(total, -1L))
        // No head laid in: the old fill, unchecked.
        assertTrue(KoVerifiedHead.lengthAgrees(-1L, 12345L))
    }

    @Test
    fun `moving a head into the cache file keeps exactly the planned bytes`() {
        val source = File(folder, "held-x.bin").also { it.writeBytes(ByteArray(300) { i -> i.toByte() }) }
        val target = File(folder, "span.exo")
        assertTrue(KoVerifiedHead.moveInto(source, target, 200L))
        assertFalse(source.exists())
        val bytes = target.readBytes()
        assertEquals(200, bytes.size)
        assertEquals(199.toByte(), bytes[199])
        // A held file shorter than the plan is refused.
        val short = File(folder, "held-y.bin").also { it.writeBytes(ByteArray(10)) }
        assertFalse(KoVerifiedHead.moveInto(short, File(folder, "span2.exo"), 20L))
    }
}
