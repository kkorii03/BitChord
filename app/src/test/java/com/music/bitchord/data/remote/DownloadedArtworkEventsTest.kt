package com.music.bitchord.data.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save signal a row waits on when its cover file lands after the first read.
 *
 * Before this existed, the only retry was a timed miss that ran when the row
 * happened to redraw — a video song's cover could arrive and the row would keep
 * the blank tile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedArtworkEventsTest {

    @Test
    fun `a saved cover reports the song id that was just filed`() = runTest {
        val received = async(UnconfinedTestDispatcher(testScheduler)) {
            DownloadedArtworkEvents.saved.first()
        }

        DownloadedArtworkEvents.coverSaved("wnRde8OQB6s")

        assertEquals("wnRde8OQB6s", received.await())
    }

    @Test
    fun `every id a video download is filed under reports the save`() = runTest {
        val received = async(UnconfinedTestDispatcher(testScheduler)) {
            DownloadedArtworkEvents.saved.take(2).toList()
        }

        DownloadedArtworkEvents.coverSaved(listOf("video-id", "song-id"))

        assertEquals(listOf("video-id", "song-id"), received.await())
    }

    @Test
    fun `a blank id is not reported`() = runTest {
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            DownloadedArtworkEvents.saved.collect { received += it }
        }

        DownloadedArtworkEvents.coverSaved(null)
        DownloadedArtworkEvents.coverSaved("")
        runCurrent()

        assertTrue(received.isEmpty())
    }
}
