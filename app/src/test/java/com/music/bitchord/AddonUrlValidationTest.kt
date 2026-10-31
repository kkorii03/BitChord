package com.music.bitchord

import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.DetectedFormat
import com.music.bitchord.data.sources.addon.SourceFormats
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a pasted URL is allowed to become an addon.
 *
 * A web page answers 200 on every path, including `/search`, so a status code
 * alone cannot tell an addon from GitHub's repository page. These tests pin the
 * three answers that matter: HTML is refused, a real search answer is accepted
 * without a manifest, and a repository URL is named as one.
 */
class AddonUrlValidationTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.requestUrl?.encodedPath.orEmpty()]
                    ?: MockResponse().setResponseCode(404)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `an HTML 200 on search is not accepted as an addon`() = runBlocking {
        route("/search", html())
        route("/", html())

        val found = SourceFormats.identify(server.url("/").toString()).getOrThrow()

        assertTrue("expected Unsupported, got $found", found is DetectedFormat.Unsupported)
        assertEquals(
            SourceFormats.NOT_ADDON_URL,
            (found as DetectedFormat.Unsupported).reason,
        )
    }

    @Test
    fun `a real search answer is accepted without a manifest`() = runBlocking {
        route(
            "/search",
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"tracks":[{"id":"t1","title":"Song","artist":"Artist"}]}"""),
        )

        val found = SourceFormats.identify(server.url("/").toString()).getOrThrow()

        assertTrue("expected Addon, got $found", found is DetectedFormat.Addon)
        assertEquals(
            AddonClient.normalizeBase(server.url("/").toString()),
            (found as DetectedFormat.Addon).baseUrl,
        )
    }

    /** A JSON object that is not a search answer is not an addon either. */
    @Test
    fun `JSON without a tracks list is refused`() = runBlocking {
        route(
            "/search",
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Not Found"}"""),
        )

        val found = SourceFormats.identify(server.url("/").toString()).getOrThrow()

        assertTrue("expected Unsupported, got $found", found is DetectedFormat.Unsupported)
        assertEquals(
            SourceFormats.NOT_ADDON_URL,
            (found as DetectedFormat.Unsupported).reason,
        )
    }

    @Test
    fun `a GitHub repository address is named as a repository page`() = runBlocking {
        val found = SourceFormats.identify("https://github.com/someone/some-addon").getOrThrow()

        assertTrue("expected Unsupported, got $found", found is DetectedFormat.Unsupported)
        assertEquals(
            SourceFormats.REPOSITORY_URL,
            (found as DetectedFormat.Unsupported).reason,
        )
    }

    /** A source saved before this check existed keeps its row, with the same reason. */
    @Test
    fun `a saved source answering HTML is rejected with the addon-address reason`() = runBlocking {
        route("/search", html())
        route("/", html())
        val source = AddonSource(
            SourceConfig(
                kind = SourceKind.ADDON,
                baseUrl = server.url("/").toString().trimEnd('/'),
            ),
        )

        val health = source.health()

        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertEquals(
            SourceFormats.NOT_ADDON_URL,
            (health as SourceHealth.Rejected).reason,
        )
    }

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    private fun html() = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<!doctype html><html><body>Search</body></html>")
}
