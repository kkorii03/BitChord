package com.music.bitchord.playback

import android.os.SystemClock
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedSongMetadata
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.smart.LyricsTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Any change of the audio clock must refresh the lyrics, including a fallback
 * to YouTube and forgetting a substitute — not only the first substitution.
 */
class StreamClockChangeSignalTest {

    private val ids = (1..7).map { "clock-signal-$it" }

    @Before
    @After
    fun reset() {
        ids.forEach(StreamChoice::forget)
        Downloads.setSavedMetadata(emptyMap())
    }

    private fun substitute(host: String, track: String = "track") = SourceStream(
        url = "https://$host/$track",
        sourceConfigId = "src",
        trackId = track,
    )

    private fun youtube(host: String) = SourceStream(url = "https://$host/track")

    private fun savedHq(videoId: String) {
        KoDownloadRecords.ensureOpen()
        Downloads.setSavedMetadata(
            mapOf(
                videoId to SavedSongMetadata(
                    videoId = videoId,
                    title = "t",
                    artist = "a",
                    uri = "file:///$videoId.flac",
                    hqSourceConfigId = "src",
                    hqTrackId = "track",
                ),
            ),
        )
    }

    private fun collectSignals(block: suspend (MutableList<String>) -> Unit): List<String> = runBlocking {
        val got = mutableListOf<String>()
        val job = launch(Dispatchers.Unconfined) {
            LyricsTiming.changes.collect { got += it }
        }
        block(got)
        yield()
        job.cancel()
        got.toList()
    }

    @Test
    fun `recording a substitute announces the change`() {
        val signals = collectSignals { _ ->
            StreamChoice.remember(ids[0], substitute("alt.example"), substituted = true)
        }

        assertEquals(listOf(ids[0]), signals)
    }

    @Test
    fun `re-recording the same substitute does not announce it again`() {
        StreamChoice.remember(ids[1], substitute("alt.example"), substituted = true)

        val signals = collectSignals { _ ->
            StreamChoice.remember(ids[1], substitute("alt.example"), substituted = true)
        }

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `falling back to youtube announces the change`() {
        StreamChoice.remember(ids[2], substitute("alt.example"), substituted = true)

        val signals = collectSignals { _ ->
            StreamChoice.remember(ids[2], youtube("googlevideo.example"), substituted = false)
        }

        assertEquals(listOf(ids[2]), signals)
    }

    @Test
    fun `forgetting a substitute announces the return to youtube`() {
        StreamChoice.remember(ids[3], substitute("alt.example"), substituted = true)

        val signals = collectSignals { _ ->
            StreamChoice.forget(ids[3])
        }

        assertEquals(listOf(ids[3]), signals)
    }

    @Test
    fun `forgetting a youtube choice that falls back to an hq download announces it`() {
        savedHq(ids[4])
        StreamChoice.remember(ids[4], youtube("googlevideo.example"), substituted = false)

        val signals = collectSignals { _ ->
            StreamChoice.forget(ids[4])
        }

        assertEquals(listOf(ids[4]), signals)
    }

    @Test
    fun `expiry of a youtube choice that falls back to an hq download announces it`() {
        savedHq(ids[5])
        StreamChoice.remember(ids[5], youtube("expiry.example"), substituted = false)

        val signals = collectSignals { _ ->
            StreamChoice.of(ids[5], nowMs = SystemClock.elapsedRealtime() + 24 * 60 * 60 * 1000L)
        }

        assertEquals(listOf(ids[5]), signals)
    }

    @Test
    fun `forgetWhere announces a youtube choice that falls back to an hq download`() {
        savedHq(ids[6])
        StreamChoice.remember(ids[6], youtube("forgetwhere.example"), substituted = false)

        val signals = collectSignals { _ ->
            StreamChoice.forgetWhere(keep = emptySet()) { it.url.contains("forgetwhere.example") }
        }

        assertEquals(listOf(ids[6]), signals)
    }
}
