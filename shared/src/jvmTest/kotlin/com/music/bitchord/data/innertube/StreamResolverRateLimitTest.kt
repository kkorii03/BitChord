package com.music.bitchord.data.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A rate-limited URL must leave the resolver's cache so the playback retry
 * re-resolves instead of opening the same paused URL again.
 */
class StreamResolverRateLimitTest {

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
}
