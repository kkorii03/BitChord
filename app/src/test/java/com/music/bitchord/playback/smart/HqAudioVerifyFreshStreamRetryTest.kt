package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.SourceStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A candidate head is fetched the way playback fetches the same URL, and a
 * definitive 403/410 gets exactly one retry after the addon mints a new URL.
 */
class HqAudioVerifyFreshStreamRetryTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = File(
            System.getProperty("java.io.tmpdir"),
            "bitchord-hq-verify-fresh-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    @Test
    fun `the verification request carries playback headers and no source range`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("candidate"))
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(
            url = server.url("/audio").toString(),
            headers = mapOf(
                "User-Agent" to "playback-agent",
                "Referer" to "https://example.test/",
            ),
        )

        assertNull(HqAudioVerify.candidateFeatures(directory, stream, OkHttpClient()))

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        requireNotNull(request)
        assertEquals("/audio", request.path)
        assertEquals("playback-agent", request.getHeader("User-Agent"))
        assertEquals("https://example.test/", request.getHeader("Referer"))
        // Playback treats a non-googlevideo source as one continuous read;
        // adding a Range header here is what made an addon refuse the head.
        assertNull(request.getHeader("Range"))
    }

    @Test
    fun `a 403 asks the addon for one fresh url and retries once`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/fresh") {
                    MockResponse().setResponseCode(200).setBody("candidate")
                } else {
                    MockResponse().setResponseCode(403)
                }
        }
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/first").toString())
        var refreshes = 0

        val result = HqAudioVerify.candidateFeatures(
            directory = directory,
            stream = stream,
            client = OkHttpClient(),
            refreshStream = { rejected ->
                refreshes += 1
                assertEquals(stream.url, rejected.url)
                SourceStream(url = server.url("/fresh").toString())
            },
        )

        assertNull(result)
        assertEquals("one fresh /stream is enough", 1, refreshes)
        assertEquals(2, server.requestCount)
        assertEquals("/first", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/fresh", server.takeRequest(5, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun `a second 403 stays unmeasured and is not retried again`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(403)
        }
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/first").toString())
        var refreshes = 0

        val result = HqAudioVerify.candidateFeatures(
            directory = directory,
            stream = stream,
            client = OkHttpClient(),
            refreshStream = {
                refreshes += 1
                SourceStream(url = server.url("/fresh").toString())
            },
        )

        assertNull("a twice-refused candidate must not be measured", result)
        assertEquals(1, refreshes)
        assertEquals(2, server.requestCount)
        assertTrue(server.requestCount <= 2)
    }
}
