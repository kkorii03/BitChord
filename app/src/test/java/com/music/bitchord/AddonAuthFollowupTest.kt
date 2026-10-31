package com.music.bitchord

import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.HqLibraryUpgrade
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The second half of the addon auth fix, after the first review.
 *
 * A refused credential has to survive all the way to the listener and to the
 * source-status screen, including when the manifest is still cached and when
 * the search row carries its own stream URL. The manual library upgrade must
 * use the auth wording instead of "no better copy", and the same request keeps
 * its twelve-second window while the automatic path stays at four.
 */
class AddonAuthFollowupTest {

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

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    private fun config(id: String = "Qobuz") = SourceConfig(
        id = id,
        kind = SourceKind.ADDON,
        label = id,
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    private val routes = mutableMapOf<String, MockResponse>()

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun manifest() = json(
        """{"id":"com.test.addon","name":"Test Addon","version":"2.1.0","resources":["search","stream"]}""",
    )

    /**
     * A manifest is public and cached for ten minutes, so a fresh `/search`
     * 401 must still make the sources screen say the credential was refused.
     */
    @Test
    fun `a search auth failure is visible on the sources screen even when the manifest is healthy`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", MockResponse().setResponseCode(401))
        installDispatcher()

        val source = AddonSource(config())
        assertTrue(
            "the search itself must fail before health is asked",
            source.search("Flowers", 10, waitForAll = false, request = StreamRequest.Best).isEmpty(),
        )

        val health = source.health()

        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertTrue(
            "the reason must point at Settings, not at the catalogue",
            (health as SourceHealth.Rejected).reason.contains("인증이 거부되었습니다"),
        )
    }

    /**
     * A row's own `streamURL` is a fallback for an addon that cannot answer
     * `/stream`, not a way around a refused credential. Returning it here made
     * the outcome look like a match and cleared the source's rest.
     */
    @Test
    fun `a stream 401 does not fall back to the search row's own URL`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """{"tracks":[{"id":"t1","title":"Flowers","artist":"Miley Cyrus",
                  "duration":200,"format":"flac","audioQuality":"LOSSLESS",
                  "streamURL":"https://cdn.example/direct.flac"}]}""",
            ),
        )
        route("/stream/t1", MockResponse().setResponseCode(401))
        installDispatcher()

        val report = SourceResolver.SearchReport()
        val found = SourceResolver.bestAcross(
            listOf(AddonSource(config())),
            target(),
            StreamRequest.Best,
            waitForAll = true,
            report = report,
        )

        assertNull("a refused stream must not be passed off as a match", found)
        assertEquals("auth", report.outcomes().single().verdict)
        assertNull("the search row's URL must not stand in for a refused stream", report.outcomes().single().stream)
        assertFalse("auth cannot confirm a miss", report.allConclusive)
        assertEquals(listOf("Qobuz"), report.authSourceNames)
    }

    /**
     * The download library's manual upgrade must name the refused addon, not
     * claim that no better copy exists.
     */
    @Test
    fun `manual library upgrade wording names the refused addon`() {
        val report = SourceResolver.SearchReport()
        report.record(
            SourceResolver.SearchOutcome(verdict = "auth", conclusive = false),
            "Qobuz",
        )

        assertEquals(
            R.string.quality_upgrade_addon_auth,
            HqLibraryUpgrade.upgradeNothingMessageId(report),
        )
    }

    @Test
    fun `manual library upgrade without auth keeps the ordinary wording`() {
        val report = SourceResolver.SearchReport()
        report.record(SourceResolver.SearchOutcome(verdict = "title"), "Qobuz")

        assertEquals(
            R.string.quality_upgrade_no_better,
            HqLibraryUpgrade.upgradeNothingMessageId(report),
        )
    }

    @Test
    fun `only a manual request gets the twelve second search window`() {
        assertEquals(4_000L, SourceResolver.sourceLookupTimeoutMs(manual = false))
        assertEquals(12_000L, SourceResolver.sourceLookupTimeoutMs(manual = true))
    }

    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
    }
}
