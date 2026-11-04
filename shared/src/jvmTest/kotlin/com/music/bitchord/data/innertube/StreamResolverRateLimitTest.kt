package com.music.bitchord.data.innertube

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A rate-limited URL must leave the resolver's cache so the playback retry
 * re-resolves instead of opening the same paused URL again. The two bounds
 * below live here for the same reason: what the resolver keeps, and what it
 * will wait for, must not grow with the length of a session.
 */
class StreamResolverRateLimitTest {

    private companion object {
        /** StreamResolver.MAX_REMEMBERED, which is private to it. */
        const val MAX_REMEMBERED = 32
    }

    @Test
    fun `a rate-limited url is forgotten and the id re-resolves`() {
        val id = "rate-limit-${System.nanoTime()}"
        val url = "https://rr4.googlevideo.com/videoplayback?rate=$id"

        StreamResolver.rememberServed(id, url)
        assertEquals(id, StreamResolver.videoIdFor(url))

        StreamResolver.onRateLimited(url)

        assertNull(StreamResolver.videoIdFor(url))
    }

    @Test
    fun `an unknown url is left alone`() {
        StreamResolver.onRateLimited("https://example.com/not-a-stream")
    }

    @Test
    fun `the playback layer is told to drop its pinned copy`() {
        val id = "rate-limit-pinned-${System.nanoTime()}"
        val url = "https://rr5.googlevideo.com/videoplayback?pinned=$id"
        StreamResolver.rememberServed(id, url)
        val told = mutableListOf<Pair<String?, String>>()
        StreamResolver.rateLimitedListener = { videoId, refused -> told += videoId to refused }
        try {
            StreamResolver.onRateLimited(url)
        } finally {
            StreamResolver.rateLimitedListener = null
        }

        assertEquals(listOf(id to url), told)
    }

    @Test
    fun `an unknown url still reaches the playback listener by url`() {
        val url = "https://module.example/audio/${System.nanoTime()}"
        val told = mutableListOf<Pair<String?, String>>()
        StreamResolver.rateLimitedListener = { videoId, refused -> told += videoId to refused }
        try {
            StreamResolver.onRateLimited(url)
        } finally {
            StreamResolver.rateLimitedListener = null
        }

        assertEquals(listOf(null to url), told)
    }

    @Test
    fun `the loudness map is bounded like its siblings`() {
        val ids = (0..(MAX_REMEMBERED + 8)).map { "loudness-${System.nanoTime()}-$it" }
        ids.forEach { StreamResolver.rememberLoudness(it, -7.0) }

        assertEquals(-7.0, StreamResolver.loudnessDbFor(ids.last()) ?: 0.0, 0.0)
        assertNull(
            "an entry past the ceiling must be dropped, not kept for the install's lifetime",
            StreamResolver.loudnessDbFor(ids.first()),
        )
    }

    @Test
    fun `a busy extraction gate is refused by the cover loop, not waited on`() = runBlocking {
        // The loop is a muted decoration for the cover; playback's extraction is
        // a track a listener is waiting for. It used to poll the gate twenty
        // times over twenty seconds instead of taking "busy" for an answer.
        val gate = StreamResolver.extractionGateForTest
        gate.lock()
        try {
            assertNull(
                withTimeout(5_000) { StreamResolver.videoLoop("busy-gate-${System.nanoTime()}") },
            )
        } finally {
            gate.unlock()
        }
    }
}
