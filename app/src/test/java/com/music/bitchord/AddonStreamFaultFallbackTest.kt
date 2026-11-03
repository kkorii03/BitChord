package com.music.bitchord

import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A `/stream` server fault is not a reason to keep playing the row's old URL.
 *
 * A search row may carry a permanent direct `streamURL` (for an addon that
 * treats `/stream` as optional). When `/stream` answers 500 or the connection
 * drops, that row URL was still returned, which made the caller count the
 * source as healthy and clear its failure streak. The addon was then asked
 * again on every track, each time failing the same endpoint.
 *
 * Only a miss (`404`/empty answer) falls back to the row's URL now; a server
 * fault counts against the source and lets the existing rest rule park it.
 */
class AddonStreamFaultFallbackTest {

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.offlineCheck = { false }
        HqHostTracker.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.offlineCheck = { AudioCache.isOffline() }
        HqHostTracker.resetForTest()
    }

    private fun config() = SourceConfig(
        id = "Qobuz",
        kind = SourceKind.ADDON,
        label = "Qobuz",
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun routeStream(status: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths += request.path.orEmpty() }
                return when {
                    request.path.orEmpty().contains("manifest") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"id":"x","name":"X","resources":["search","stream"]}""",
                        )
                    request.path.orEmpty().contains("/search") -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"tracks":[{"id":"t1","title":"Flowers",
                               "artist":"Miley Cyrus","duration":200,"format":"flac",
                               "streamURL":"https://cdn.example/direct.flac"}]}""",
                        )
                    else -> MockResponse().setResponseCode(status)
                }
            }
        }
    }

    private fun streamCalls(): Int = synchronized(paths) {
        paths.count { it.startsWith("/stream/") }
    }

    private suspend fun searched() = AddonSource(config()).also {
        it.search("Flowers", limit = 5, waitForAll = false, request = StreamRequest.Best)
    }

    @Test
    fun `a 500 on stream is a fault, not a reason to use the row URL`() = runBlocking {
        routeStream(500)
        val source = searched()

        assertNull(source.stream("t1", StreamRequest.Lossless))
        assertEquals("a server fault must count against the source", 1L, SourceResolver.SourceCooldown.failureMark("Qobuz"))
    }

    @Test
    fun `a stream fault stops being asked after the rest rule parks the source`() = runBlocking {
        routeStream(500)
        val source = AddonSource(config())
        val target = com.music.bitchord.data.sources.TrackMatcher.Target(
            "Flowers",
            "Miley Cyrus",
            durationSec = 200,
        )

        repeat(2) {
            assertNull(
                SourceResolver.bestAcross(listOf(source), target, StreamRequest.Best, waitForAll = true),
            )
        }
        assertTrue("two faults must park the source", SourceResolver.SourceCooldown.isResting("Qobuz"))

        val before = streamCalls()
        assertNull(SourceResolver.bestAcross(listOf(source), target, StreamRequest.Best, waitForAll = true))
        assertEquals("a resting source must not be called again", before, streamCalls())
    }

    @Test
    fun `a 404 on stream still falls back to the row URL`() = runBlocking {
        routeStream(404)
        val source = searched()

        assertEquals("https://cdn.example/direct.flac", source.stream("t1", StreamRequest.Best)?.url)
    }
}
