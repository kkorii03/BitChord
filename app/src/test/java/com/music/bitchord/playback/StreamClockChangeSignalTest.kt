package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
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

    private val ids = (1..4).map { "clock-signal-$it" }

    @Before
    @After
    fun reset() {
        ids.forEach(StreamChoice::forget)
    }

    private fun substitute(host: String, track: String = "track") = SourceStream(
        url = "https://$host/$track",
        sourceConfigId = "src",
        trackId = track,
    )

    private fun youtube(host: String) = SourceStream(url = "https://$host/track")

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
}
