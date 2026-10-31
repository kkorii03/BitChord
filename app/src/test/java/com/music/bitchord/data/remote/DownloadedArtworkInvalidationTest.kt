package com.music.bitchord.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A cover save must reach both a row that is still drawing and one that is not.
 *
 * The event itself wakes a composed row. Retiring the remembered answer at save
 * time is what makes a row that was off-screen — or a read that was already
 * running when the file landed — see the new cover instead of the old miss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedArtworkInvalidationTest {

    private val videoId = "wnRde8OQB6s"
    private val prefix = "$videoId\u0001"

    @Test
    fun `a save retires an in-flight read so its stale answer is not remembered`() = runTest {
        val memo = ArtworkAnswerMemo(8)
        val key = "${prefix}file:///music/a.m4a\u0001file:///art.jpg\u0001"
        val staleReadMayFinish = CompletableDeferred<Unit>()
        var reads = 0

        val beforeSave = async {
            memo.resolve(key) {
                reads++
                staleReadMayFinish.await()
                null
            }
        }
        runCurrent()
        assertEquals(1, reads)

        memo.invalidatePrefix(prefix)
        val afterSave = async {
            memo.resolve(key) {
                reads++
                "file:///art.jpg"
            }
        }
        runCurrent()

        assertEquals("file:///art.jpg", afterSave.await())
        staleReadMayFinish.complete(Unit)
        assertEquals("file:///art.jpg", beforeSave.await())
        assertEquals("the stale read is not re-run", 2, reads)

        assertEquals("file:///art.jpg", memo.resolve(key) { reads++; null })
        assertEquals("the fresh answer is remembered", 2, reads)
    }

    @Test
    fun `a save retires a remembered miss for every key of that video id`() = runTest {
        val memo = ArtworkAnswerMemo(8)
        assertEquals(null, memo.resolve("${prefix}file:///a") { null })
        assertEquals(null, memo.resolve("${prefix}file:///b") { null })

        memo.invalidatePrefix(prefix)

        var reads = 0
        assertEquals(
            "file:///late.jpg",
            memo.resolve("${prefix}file:///a") { reads++; "file:///late.jpg" },
        )
        assertEquals("a row composing after an off-screen save reads again", 1, reads)
    }

    @Test
    fun `a saved cover reports the song id that was just filed`() = runTest {
        val received = async(UnconfinedTestDispatcher(testScheduler)) {
            DownloadedArtworkEvents.saved.first()
        }

        DownloadedArtworkEvents.coverSaved(videoId)

        assertEquals(videoId, received.await())
    }
}
