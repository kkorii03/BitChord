package com.music.bitchord.ko

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * dupdl: 11-19 02:23 Die With A Smile (DlFXDl_ROAM). The sound check kept a
 * 19,260,150-byte head of the 26,967,136-byte FLAC "for the swap", then
 * `hq-ahead: re-prepared next player as FLAC` and the next player fetched the
 * whole file from byte 0 again: the head was only ever taken by the mid-song
 * swap's own fill, never by a player opening the upgraded URI. Twelve such heads
 * (19-31 MB each) were thrown away that night.
 */
class KoPlayerSeedTest {

    private lateinit var folder: File

    private val url = "https://addon.example/flac/DlFXDl_ROAM?t=1"
    private val total = 26_967_136L
    private val headBytes = 19_260_150L

    @Before
    fun setUp() {
        folder = Files.createTempDirectory("player_seed").toFile()
        KoVerifiedHead.detachAll().forEach { it.delete() }
        listOf("k1", "k2", "k3", "k4", "k5", "DlFXDl_ROAM#hifi").forEach(KoVerifiedHead::forgetPromise)
    }

    @After
    fun tearDown() {
        KoVerifiedHead.detachAll().forEach { it.delete() }
        listOf("k1", "k2", "k3", "k4", "k5", "DlFXDl_ROAM#hifi").forEach(KoVerifiedHead::forgetPromise)
        folder.deleteRecursively()
    }

    private fun head(bytes: Long): File =
        File(folder, "candidate.bin").also { file -> RandomAccessFile(file, "rw").use { it.setLength(bytes) } }

    @Test
    fun `a player's open sees the accepted head of exactly its stream, without taking it`() {
        val entry = KoVerifiedHead.offer(head(headBytes), url, "src", "row-1", total)
        assertNotNull(entry)
        assertFalse("a head the check is still on is not the player's", KoVerifiedHead.holdsAccepted(url, "src", "row-1"))
        assertTrue(KoVerifiedHead.accept(entry!!, "DlFXDl_ROAM"))
        assertTrue(KoVerifiedHead.holdsAccepted(url, "src", "row-1"))
        assertFalse(KoVerifiedHead.holdsAccepted("$url&other", "src", "row-1"))
        assertFalse(KoVerifiedHead.holdsAccepted(url, "other", "row-1"))
        assertFalse(KoVerifiedHead.holdsAccepted(url, "src", "row-2"))
        // Asking leaves it held: only the seed's own take removes it.
        assertEquals(1, KoVerifiedHead.heldCount())
        assertTrue(entry.file.isFile)
    }

    @Test
    fun `the length the check saw is checked once the player's fill states one`() {
        val key = "DlFXDl_ROAM#hifi"
        KoVerifiedHead.promise(key, url, total)
        assertTrue(KoVerifiedHead.hasPromises())
        assertEquals(total, KoVerifiedHead.promisedLength(key, url))
        assertEquals(-1L, KoVerifiedHead.promisedLength(key, "$url&other"))
        // Nothing stated yet (the player has not reached the rest): no verdict, kept.
        assertEquals(KoVerifiedHead.Splice.NONE, KoVerifiedHead.settle(key, url, -1L))
        assertEquals(total, KoVerifiedHead.promisedLength(key, url))
        // The same file: settled.
        assertEquals(KoVerifiedHead.Splice.AGREES, KoVerifiedHead.settle(key, url, total))
        assertEquals(-1L, KoVerifiedHead.promisedLength(key, url))
        assertFalse(KoVerifiedHead.hasPromises())
    }

    @Test
    fun `another length is a splice, kept until the entry is dropped`() {
        val key = "DlFXDl_ROAM#hifi"
        KoVerifiedHead.promise(key, url, total)
        assertEquals(KoVerifiedHead.Splice.DIFFERS, KoVerifiedHead.settle(key, url, total + 1))
        // A reader still held the entry: the next close asks again.
        assertEquals(KoVerifiedHead.Splice.DIFFERS, KoVerifiedHead.settle(key, url, total + 1))
        KoVerifiedHead.forgetPromise(key)
        assertEquals(KoVerifiedHead.Splice.NONE, KoVerifiedHead.settle(key, url, total + 1))
    }

    @Test
    fun `a promise made for another url is stale and dropped`() {
        val key = "DlFXDl_ROAM#hifi"
        KoVerifiedHead.promise(key, url, total)
        assertEquals(KoVerifiedHead.Splice.NONE, KoVerifiedHead.settle(key, "$url&refilled", total + 5))
        assertEquals(-1L, KoVerifiedHead.promisedLength(key, url))
    }

    @Test
    fun `at most four promises are kept`() {
        listOf("k1", "k2", "k3", "k4", "k5").forEach { KoVerifiedHead.promise(it, url, total) }
        assertEquals(-1L, KoVerifiedHead.promisedLength("k1", url))
        assertEquals(total, KoVerifiedHead.promisedLength("k5", url))
    }

    @Test
    fun `the player's cache open lays the head in and its close checks the length`() {
        val cache = source("src/main/java/com/music/bitchord/playback/AudioCache.kt")
        val factory = cache.substringAfter("private fun cacheFactory(upstream: DataSource.Factory)")
            .substringBefore("private fun readAheadCacheFactory(")
        val open = factory.substringAfter("override fun open(dataSpec: DataSpec): Long {").substringBefore("override fun read(")
        assertTrue("the open lays in the head", "koLossless.seedForPlayer(dataSpec.uri)" in open)
        assertTrue(
            "before the cache reads byte 0",
            open.indexOf("koLossless.seedForPlayer(dataSpec.uri)") < open.indexOf("return inner.open(dataSpec)"),
        )
        val close = factory.substringAfter("override fun close()")
        assertTrue("the close checks the stated length", "koLossless::checkPlayerSeed" in close)

        val lossless = source("src/main/java/com/music/bitchord/ko/KoLosslessCache.kt")
        val seed = lossless.substringAfter("fun seedForPlayer(uri: Uri) {").substringBefore("\n    }\n")
        assertTrue("only an upgraded URI's parked stream", "QualityUpgrade.forcedStream(uri)" in seed)
        assertTrue(
            "asked before taking, so an open that cannot use it leaves it",
            seed.indexOf("KoVerifiedHead.holdsAccepted(") in 0 until seed.indexOf("seedFromVerifiedHead("),
        )
        assertTrue(
            "only into an empty entry",
            seed.indexOf("cachedBytes != 0L || contentLengthOf(key) > 0L") in 0 until seed.indexOf("seedFromVerifiedHead("),
        )
        assertTrue("the length is promised", "KoVerifiedHead.promise(key, stream.url, total)" in seed)
        val fill = lossless.substringAfter("private suspend fun cacheWholeRenditionDetailed(").substringBefore("fun prefetchVerifiedRendition(")
        assertTrue("a whole-file fill of a seeded entry checks the promise", "KoVerifiedHead.promisedLength(key, stream?.url)" in fill)
        val check = lossless.substringAfter("fun checkPlayerSeed(uri: Uri) {").substringBefore("\n    }\n")
        assertTrue("a splice is dropped", "discardRendition(uri)" in check.substringAfter("Splice.DIFFERS"))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }
}
