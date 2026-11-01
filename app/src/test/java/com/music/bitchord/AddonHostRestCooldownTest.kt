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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A sleeping gateway's short host rest must not become a thirty-minute source
 * rest.
 *
 * Two 503s used to park the whole addon for [SourceResolver]'s cooldown even
 * though the host itself was rested for only a minute or two. The server could
 * wake and serve the next track, but its candidates stayed out of the search
 * until the cooldown expired.
 */
class AddonHostRestCooldownTest {

    private lateinit var server: MockWebServer

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

    private fun manifest() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"id":"com.test.addon","name":"Test Addon","version":"2.1.0","resources":["search","stream"]}""",
        )

    private fun sleepingSearch() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("manifest")) {
                    manifest()
                } else {
                    MockResponse().setResponseCode(503)
                }
        }
    }

    @Test
    fun `a sleeping gateway rests the source only as long as the host`() = runBlocking {
        sleepingSearch()

        // Two separate clients so the second search is not held by the first
        // client's own quiet period; the source cooldown is keyed by config id.
        repeat(2) {
            AddonSource(config()).search("Flowers", 10, waitForAll = false, request = StreamRequest.Best)
        }

        val now = System.currentTimeMillis()
        assertTrue(
            "the source must still respect the host's rest",
            SourceResolver.SourceCooldown.isResting("Qobuz", now + 30_000L),
        )
        assertFalse(
            "a host that wakes in minutes must not keep the source out for thirty",
            SourceResolver.SourceCooldown.isResting("Qobuz", now + 20 * 60_000L),
        )
    }

    @Test
    fun `a failure with no host rest keeps the full source cooldown`() {
        val cooldown = SourceResolver.SourceCooldown
        val now = System.currentTimeMillis()
        cooldown.failed("Qobuz")
        cooldown.failed("Qobuz")

        assertTrue(cooldown.isResting("Qobuz", now + 29 * 60_000L))
        assertFalse(cooldown.isResting("Qobuz", now + 31 * 60_000L))
    }
}
