package com.music.bitchord.data.lyrics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A provider that refuses or times out must reach the sheet as BLOCKED or
 * TIMEOUT, not as an ordinary "not found". These statuses used to be turned
 * into a null inside the HTTP helper, so the failure classifier never saw them.
 */
class LyricsHttpStatusTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun url() = server.url("/lyrics").toString()

    @Test
    fun aRefusalSurfacesAsBlockedInsteadOfNotFound() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setResponseCode(429))

        val forbidden = assertFailsWith<LyricsHttpStatusException> { lyricsGet(url()) }
        assertEquals(403, forbidden.code)
        assertEquals(LyricsFailure.BLOCKED, LyricsFailure.from(forbidden))

        val throttled = assertFailsWith<LyricsHttpStatusException> {
            lyricsGetBearer(url(), "token")
        }
        assertEquals(429, throttled.code)
        assertEquals(LyricsFailure.BLOCKED, LyricsFailure.from(throttled))
    }

    @Test
    fun aDeadConnectionSurfacesAsNetwork() {
        val port = java.net.ServerSocket(0).use { it.localPort }

        val error = assertFailsWith<java.io.IOException> {
            lyricsGet("http://127.0.0.1:$port/lyrics")
        }
        assertEquals(LyricsFailure.NETWORK, LyricsFailure.from(error))
    }

    @Test
    fun anOrdinaryMissAndASuccessfulBodyKeepWorking() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        assertNull(lyricsGetAsBrowser(url(), "https://example.com", "application/json"))
        assertEquals("""{"ok":true}""", lyricsGet(url()))
    }
}
