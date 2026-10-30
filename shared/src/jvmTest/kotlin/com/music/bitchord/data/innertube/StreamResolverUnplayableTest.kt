package com.music.bitchord.data.innertube

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A URL remembered before a permanent verdict must not outlive that verdict.
 *
 * Every caller above the resolver treats an unplayable answer as permission to
 * move on immediately; handing back the dead URL instead costs one more load
 * and one more failure to learn what was already known.
 */
class StreamResolverUnplayableTest {

    @After
    fun clear() {
        StreamResolver.onSessionChanged()
    }

    @Test
    fun `a cached URL is not returned after an unplayable verdict`() {
        val videoId = "unplayable-cache-test"
        StreamResolver.rememberServed(
            videoId,
            "https://rr1.googlevideo.com/videoplayback?clen=1",
        )
        StreamResolver.markUnplayable(videoId, "blocked in this region")

        assertThrows(StreamResolver.PermanentlyUnplayableException::class.java) {
            runBlocking { StreamResolver.resolve(videoId) }
        }
    }
}
