package com.music.bitchord

import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
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
 * The two MEDIUM findings from the 10-31 review of the addon auth fix.
 *
 * A fresh [AddonSource] had no local auth mark, so a healthy manifest plus a
 * refused `/search` still reported `Ok` long enough to hide the fix. And a
 * refused `/stream` returned null, letting [SourceResolver.streamBest] spend
 * one call on each of up to three matching rows.
 */
class AddonAuthReviewTest {

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

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    private fun config(id: String = "Qobuz") = SourceConfig(
        id = id,
        kind = SourceKind.ADDON,
        label = id,
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun manifest() = json(
        """{"id":"com.test.addon","name":"Test Addon","version":"2.1.0","resources":["search","stream"]}""",
    )

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val asked = request.requestUrl?.encodedPath.orEmpty()
                synchronized(paths) { paths += asked }
                return routes[asked] ?: MockResponse().setResponseCode(404)
            }
        }
    }

    private val routes = mutableMapOf<String, MockResponse>()

    @Test
    fun `a fresh instance still reports a refused search on the sources screen`() = runBlocking {
        route("/manifest.json", manifest())
        route("/search", MockResponse().setResponseCode(401))

        val health = AddonSource(config()).health()

        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertTrue(
            "the manifest being healthy must not hide the refused credential",
            (health as SourceHealth.Rejected).reason.contains("인증이 거부되었습니다"),
        )
    }

    @Test
    fun `a refused stream stops at the first matching row`() = runBlocking {
        route("/manifest.json", manifest())
        route(
            "/search",
            json(
                """{"tracks":[
                  {"id":"t1","title":"Flowers","artist":"Miley Cyrus","duration":200,
                   "format":"flac","audioQuality":"LOSSLESS"},
                  {"id":"t2","title":"Flowers","artist":"Miley Cyrus","duration":200,
                   "format":"flac","audioQuality":"LOSSLESS"},
                  {"id":"t3","title":"Flowers","artist":"Miley Cyrus","duration":200,
                   "format":"flac","audioQuality":"LOSSLESS"}]}""",
            ),
        )
        for (id in listOf("t1", "t2", "t3")) {
            route("/stream/$id", MockResponse().setResponseCode(401))
        }

        val report = SourceResolver.SearchReport()
        val found = SourceResolver.bestAcross(
            listOf(AddonSource(config())),
            target(),
            StreamRequest.Best,
            waitForAll = true,
            report = report,
        )

        assertNull("a refused credential is not a match", found)
        assertEquals("auth", report.outcomes().single().verdict)
        assertEquals(
            "a refused credential must not be retried on the other matching rows",
            1,
            synchronized(paths) { paths.count { it.startsWith("/stream/") } },
        )
    }
}
