package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The resolved-URL cache is what a retry reads before it resolves again. A
 * server that asked for a long wait must not be handed the same URL back.
 */
class StreamResolverCacheTest {

    @Test
    fun `a long retry-after forgets the cached URL so the next retry resolves again`() {
        val videoId = "retry-test-video"
        val url = "https://rr1.example.invalid/audio?itag=251&retry=1"
        StreamResolver.rememberServed(videoId, url)
        assertEquals(videoId, StreamResolver.videoIdFor(url))

        StreamResolver.forgetRateLimitedUrl(url)

        assertNull(StreamResolver.videoIdFor(url))
    }

    @Test
    fun `forgetting an unknown URL changes nothing`() {
        StreamResolver.forgetRateLimitedUrl("https://rr1.example.invalid/not-cached")

        assertNull(StreamResolver.videoIdFor("https://rr1.example.invalid/not-cached"))
    }
}
