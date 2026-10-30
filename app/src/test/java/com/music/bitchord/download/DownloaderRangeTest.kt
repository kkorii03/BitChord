package com.music.bitchord.download

import com.music.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A download through a proxy or cache that does not honour Range.
 *
 * googlevideo always answers a ranged request with 206, but a configured proxy
 * is free to answer 200 with the whole file. The old code took only the first
 * `length` bytes of that response and asked for the next range, writing the
 * opening of the track into the middle of the file. It also measured an
 * unknown-length URL only from Content-Range, so a 200 response made the
 * download fail before its first byte.
 */
class DownloaderRangeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stream(url: String) = StreamResolver.Stream(
        url = url,
        kbps = 128,
        mimeType = "audio/mp4",
    )

    @Test
    fun `a 200 response with content length is a whole track, not an unknown length`() = runBlocking {
        val body = ByteArray(64) { it.toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(Buffer().write(body))
        }

        val out = ByteArrayOutputStream()
        val written = Downloader.fetch(
            videoId = "video",
            stream = stream(server.url("/audio").toString()),
            maxKbps = 128,
            requireM4a = false,
            sink = out,
            onProgress = { _, _ -> },
        )

        assertEquals(body.size.toLong(), written)
        assertArrayEquals(body, out.toByteArray())
    }

    @Test
    fun `a later range answered with 200 continues instead of duplicating the opening`() = runBlocking {
        val firstChunk = 2 * 1024 * 1024
        val total = firstChunk + 100
        val body = ByteArray(total) { index -> (index * 31).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range").orEmpty()
                return if (range.startsWith("bytes=0-")) {
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-${firstChunk - 1}/$total")
                        .setBody(Buffer().write(body, 0, firstChunk))
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(body))
                }
            }
        }

        val out = ByteArrayOutputStream()
        val written = Downloader.fetch(
            videoId = "video",
            stream = stream(server.url("/audio?clen=$total").toString()),
            maxKbps = 128,
            requireM4a = false,
            sink = out,
            onProgress = { _, _ -> },
        )

        assertEquals(total.toLong(), written)
        assertArrayEquals(body, out.toByteArray())
    }
}
