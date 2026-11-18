package com.music.bitchord.ko

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dupdl: 11-19 02:21 Cruel Summer (aC9HkZW2hZk), a 37 MB FLAC on metered LTE.
 * The player filled the song cache from the addon URL while a queued download
 * resolved that same URL and fetched the whole file a second time. The download
 * now takes the player's fill, but only a fill of exactly that URL happening now.
 */
class KoPlayersFillTest {

    private data class Copy(val extension: String)

    private val url = "https://addon.example/flac/aC9HkZW2hZk?t=1"

    private class World(
        var pinned: String?,
        var holds: Boolean,
        var wholeBefore: Copy?,
        var wholeAfter: Copy?,
        val pinAfter: String? = pinned,
    ) {
        var released = false
        var waits = 0
        val lines = mutableListOf<String>()
    }

    private fun run(world: World, extension: String = "flac"): Copy? = runBlocking {
        KoPlayersFill.copyOf(
            url = url,
            extension = extension,
            pinnedUrl = { world.pinned },
            playerHolds = { world.holds },
            whole = { if (world.released) world.wholeAfter else world.wholeBefore },
            extensionOf = { it.extension },
            awaitRelease = {
                world.waits++
                world.released = true
                world.pinned = world.pinAfter
            },
            log = { world.lines += it },
        )
    }

    @Test
    fun `the player's fill of the same url is copied once it lets go`() {
        val world = World(pinned = url, holds = true, wholeBefore = null, wholeAfter = Copy("flac"))
        assertEquals(Copy("flac"), run(world))
        assertEquals(1, world.waits)
        assertTrue(world.lines.any { "copying the player's fill" in it })
    }

    @Test
    fun `another pinned stream is fetched, no wait`() {
        val world = World(pinned = "$url&other", holds = true, wholeBefore = null, wholeAfter = Copy("flac"))
        assertNull(run(world))
        assertEquals(0, world.waits)
        val nothingPinned = World(pinned = null, holds = true, wholeBefore = null, wholeAfter = Copy("flac"))
        assertNull(run(nothingPinned))
        assertEquals(0, nothingPinned.waits)
    }

    @Test
    fun `no player on the entry is fetched, no wait`() {
        val world = World(pinned = url, holds = false, wholeBefore = null, wholeAfter = Copy("flac"))
        assertNull(run(world))
        assertEquals(0, world.waits)
    }

    @Test
    fun `an entry already whole has no proof of its url and is fetched`() {
        val world = World(pinned = url, holds = true, wholeBefore = Copy("flac"), wholeAfter = Copy("flac"))
        assertNull(run(world))
        assertEquals(0, world.waits)
    }

    @Test
    fun `a pin that moved during the wait is fetched`() {
        val world = World(pinned = url, holds = true, wholeBefore = null, wholeAfter = Copy("flac"), pinAfter = "$url&swap")
        assertNull(run(world))
        assertTrue(world.lines.any { "moved to another stream" in it })
    }

    @Test
    fun `a fill the player left part way is fetched`() {
        val world = World(pinned = url, holds = true, wholeBefore = null, wholeAfter = null)
        assertNull(run(world))
        assertTrue(world.lines.any { "not whole" in it })
    }

    @Test
    fun `another container is fetched`() {
        val world = World(pinned = url, holds = true, wholeBefore = null, wholeAfter = Copy("m4a"))
        assertNull(run(world, extension = "flac"))
    }

    @Test
    fun `a blank url never waits`() {
        val world = World(pinned = "", holds = true, wholeBefore = null, wholeAfter = Copy("flac"))
        val copy = runBlocking {
            KoPlayersFill.copyOf(
                url = "", extension = "flac", pinnedUrl = { world.pinned }, playerHolds = { world.holds },
                whole = { world.wholeAfter }, extensionOf = { it.extension },
                awaitRelease = { world.waits++ }, log = { world.lines += it },
            )
        }
        assertNull(copy)
        assertEquals(0, world.waits)
    }

    @Test
    fun `the direct download asks for the player's fill before a second fetch`() {
        val pipeline = source("src/main/java/com/music/bitchord/ko/downloads/KoDownloadPipeline.kt")
        val ask = pipeline.indexOf("AudioCache.playersFillOf(track.videoId, stream.url, storable.extension)")
        val fetch = pipeline.indexOf("Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)")
        assertTrue("the download asks the player's fill", ask >= 0)
        assertTrue("before fetching", fetch > ask)
        assertTrue("and copies it from disk", "AudioCache.copyOut(fill, sink, onProgress)" in pipeline.substring(ask, fetch))

        val cache = source("src/main/java/com/music/bitchord/playback/AudioCache.kt")
        val fill = cache.substringAfter("suspend fun playersFillOf(").substringBefore("\n    }\n")
        assertTrue("only the stream the song is pinned to", "StreamChoice.of(videoId)?.url" in fill)
        assertTrue("only while the player holds it", "koTracker.playerHoldsKey(key)" in fill)
        assertTrue("waits on the release event, no timer", "koTracker.awaitRelease(key)" in fill)
        assertFalse("no polling", "delay(" in fill)
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }
}
