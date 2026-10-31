package com.music.bitchord

import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.addon.DetectedFormat
import com.music.bitchord.data.sources.addon.SourceFormats
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
 * Second pass over the addon-address check.
 *
 * The first pass only refused HTML when it carried a `text/html` header and
 * refused every code-host URL before looking at its path. These tests pin the
 * two gaps that left: a body that fails to parse is a refusal even without the
 * header, and a raw document on a code host is not a repository page. They also
 * keep a transient probe failure from being painted as a permanent rejection.
 */
class AddonUrlValidationR2Test {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        HqHostTracker.resetForTest()
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
        HqHostTracker.resetForTest()
    }

    @Test
    fun `an HTML 200 on search without a content type is refused`() = runBlocking {
        route("/", page())
        route("/search", page())

        val found = SourceFormats.identify(server.url("/").toString()).getOrThrow()

        assertTrue("expected Unsupported, got $found", found is DetectedFormat.Unsupported)
        assertEquals(
            SourceFormats.NOT_ADDON_URL,
            (found as DetectedFormat.Unsupported).reason,
        )
    }

    @Test
    fun `a saved source answering HTML without a content type names the addon reason`() = runBlocking {
        route("/", page())
        route("/search", page())
        val source = AddonSource(sourceConfig())

        val health = source.health()

        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertEquals(
            SourceFormats.NOT_ADDON_URL,
            (health as SourceHealth.Rejected).reason,
        )
    }

    @Test
    fun `a raw document on a code host is not a repository page`() {
        assertNull(
            SourceFormats.repositoryReason("https://github.com/someone/addon/raw/main/index.json"),
        )
        assertNull(
            SourceFormats.repositoryReason("https://gitlab.com/someone/addon/-/raw/main/index.json"),
        )
        assertEquals(
            SourceFormats.REPOSITORY_URL,
            SourceFormats.repositoryReason("https://github.com/someone/some-addon"),
        )
    }

    @Test
    fun `a transient probe failure stays unreachable instead of rejected`() = runBlocking {
        route("/manifest.json", MockResponse().setResponseCode(500))
        route("/search", MockResponse().setResponseCode(200))
        val source = AddonSource(sourceConfig())

        val health = source.health()

        assertTrue("expected Unreachable, got $health", health is SourceHealth.Unreachable)
    }

    private fun sourceConfig() = SourceConfig(
        kind = SourceKind.ADDON,
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun route(path: String, response: MockResponse) {
        routes[path] = response
    }

    /** A web page that answers 200 without saying what it is. */
    private fun page() = MockResponse()
        .setBody("<!doctype html><html><body>Search</body></html>")
}
