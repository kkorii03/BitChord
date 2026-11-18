package com.music.bitchord.ko.downloads

import com.music.bitchord.download.Downloads
import com.music.bitchord.ko.KoPlayersFill
import com.music.bitchord.playback.HqHostTracker
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 11-19 F2: an automatic download of the song playing now waits for the
 * player's own fill of the same file (dupdl). The player lets go of that entry
 * only when its loader reaches the end of the file (its read-ahead is capped at
 * 4 MB on cellular, about 20 s of 24-bit FLAC), so the wait lasts most of the
 * song. It used to run inside the write, under the host slot and the
 * candidate-host lock, so the next track's read-ahead
 * (`HqHostTracker.awaitIdle` in KoPrefetchQueue) and sound checks on that host
 * waited with it. The wait makes no request: it now runs before the slot.
 */
class KoPlayersFillWaitTest {

    private val host = "hr-front.example.dev"

    @Before
    fun reset() = HqHostTracker.resetForTest()

    @After
    fun clean() = HqHostTracker.resetForTest()

    private fun route(beforeHostSlot: (suspend () -> Unit)?) = Downloads.Route(
        extension = "flac",
        mimeType = "audio/flac",
        describe = "FLAC 24-bit",
        host = host,
        write = { _, _ -> },
        beforeHostSlot = beforeHostSlot,
    )

    @Test
    fun `a download waiting for the player's fill holds no host slot`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val playerLetGo = CompletableDeferred<Unit>()
        val begun = async {
            KoDownloadPipeline.beginAutomaticDownload(
                "aC9HkZW2hZk",
                route {
                    entered.complete(Unit)
                    playerLetGo.await()
                },
            )
        }
        entered.await()
        assertFalse(
            "the waiting download must not own the host",
            HqHostTracker.isBusy(host, exceptTrack = "DlFXDl_ROAM"),
        )
        // The next track's read-ahead gate (KoPrefetchQueue) passes at once.
        assertNotNull(
            "the next track's read-ahead waited on a download that was only waiting",
            withTimeoutOrNull(1_000) { HqHostTracker.awaitIdle(host, exceptTrack = "DlFXDl_ROAM") },
        )

        playerLetGo.complete(Unit)
        assertEquals(host, begun.await())
        assertTrue("the slot is taken once the wait is over", HqHostTracker.isBusy(host, exceptTrack = "DlFXDl_ROAM"))
        HqHostTracker.endDownload("aC9HkZW2hZk", host)
        assertFalse(HqHostTracker.isBusy(host))
    }

    @Test
    fun `a resting host defers before any wait`() = runBlocking {
        HqHostTracker.rest(host, 60_000L)
        var waited = false
        val deferred = runCatching {
            KoDownloadPipeline.beginAutomaticDownload("aC9HkZW2hZk", route { waited = true })
        }.exceptionOrNull()
        assertTrue(deferred is KoDownloadPipeline.AutoDownloadDeferred)
        assertFalse("a resting host is not waited on", waited)
        assertFalse(HqHostTracker.isBusy(host))
    }

    @Test
    fun `the player's fill is asked once and the write reads the same answer`() = runBlocking {
        var asks = 0
        val answer = Any()
        val asked = KoPlayersFill.Asked {
            asks++
            answer
        }
        assertSame(answer, asked.get()) // before the slot
        assertSame(answer, asked.get()) // the write: no second wait
        assertEquals(1, asks)

        var nullAsks = 0
        val none = KoPlayersFill.Asked<Any> {
            nullAsks++
            null
        }
        none.get()
        none.get()
        assertEquals("a null answer (fetch) is remembered too", 1, nullAsks)
    }

    @Test
    fun `the wait runs before the slot and both ask the same answer`() {
        val pipeline = source("src/main/java/com/music/bitchord/ko/downloads/KoDownloadPipeline.kt")
        val begin = pipeline.substringAfter("suspend fun beginAutomaticDownload(").substringBefore("\n    }\n")
        val wait = begin.indexOf("route.beforeHostSlot?.invoke()")
        assertTrue("the automatic pass runs the wait", wait >= 0)
        assertTrue("after the rest check", begin.indexOf("throw AutoDownloadDeferred(rest)") in 0 until wait)
        assertTrue("before the host is awaited", begin.indexOf("HqHostTracker.awaitIdle(host, exceptTrack = id)") > wait)
        assertTrue("before the slot is taken", begin.indexOf("HqHostTracker.beginDownload(id, host)") > wait)

        val ask = pipeline.indexOf("KoPlayersFill.Asked<AudioCache.CompleteCopy>")
        val route = pipeline.substring(ask)
        assertTrue("the write reads the asked answer", "val fill = playersFill.get()" in route)
        assertTrue("the route hands the same ask to the pass", "beforeHostSlot = { playersFill.get() }" in route)

        val downloads = source("src/main/java/com/music/bitchord/download/Downloads.kt")
        val slot = downloads.indexOf("KoDownloadPipeline.beginAutomaticDownload(id, plan.route)")
        val lock = downloads.indexOf("KoDownloadPipeline.transferAutomatic(automatic, host)")
        assertTrue("the candidate-host lock is taken after the slot", slot in 0 until lock)
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }
}
