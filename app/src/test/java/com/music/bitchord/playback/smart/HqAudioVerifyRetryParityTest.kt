package com.music.bitchord.playback.smart

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The verification fetch and a fresh retry both copy what playback does.
 *
 * Playback range-chunks exactly the URLs that carry a numeric `clen`; keying
 * the decision off the host made an addon URL with `clen` get no Range in
 * verification while playback sent one, and the edge refused it with 403. A
 * retry of that refusal must also ask for the tier and mix the listener
 * originally chose, not one guessed back from what the first URL served.
 */
class HqAudioVerifyRetryParityTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = File(
            System.getProperty("java.io.tmpdir"),
            "bitchord-hq-verify-parity-${System.nanoTime()}",
        ).apply { mkdirs() }
        DeviceCodecs.forced = true
        AppSettings.dolbyAtmos.value = true
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
        DeviceCodecs.forced = null
        AppSettings.dolbyAtmos.value = true
    }

    @Test
    fun `a url with clen is range fetched like playback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("candidate"))
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/audio?clen=524288").toString())

        assertNull(HqAudioVerify.candidateFeatures(directory, stream, OkHttpClient()))

        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/audio?clen=524288", request.path)
        assertEquals("bytes=0-", request.getHeader("Range"))
    }

    @Test
    fun `a fresh retry keeps the original tier and stereo pin`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/stream/t1") {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"url":"https://cdn.example.com/fresh.flac","codec":"flac"}""")
                } else {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(MANIFEST)
                }
        }
        val config = SourceConfig(
            kind = SourceKind.ADDON,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        val addon = AddonSource(config)
        val refused = SourceStream(
            url = "https://cdn.example.com/refused.flac",
            format = StreamFormat(codec = "flac", kbps = 1411),
            request = StreamRequest.Capped(128),
            stereoOnly = true,
            sourceConfigId = config.id,
            trackId = "t1",
        )

        val fresh = HqAudioVerify.freshAddonStream(refused) { addon }

        assertNotNull(fresh)
        val streamRequest = (0 until server.requestCount)
            .mapNotNull { server.takeRequest(5, TimeUnit.SECONDS) }
            .first { it.requestUrl?.encodedPath == "/stream/t1" }
        val query = requireNotNull(streamRequest.requestUrl)
        // The original Capped(128) is re-asked, not the lossless FLAC it served.
        assertEquals("low", query.queryParameter("quality"))
        // The listener's stereo row stays stereo; no Atmos hint may replace it.
        assertEquals("off", query.queryParameter("atmos"))
    }

    private companion object {
        const val MANIFEST = """
            {"id":"com.test.addon","name":"Test Addon","version":"2.1.0",
             "resources":["search","stream"],
             "settings":[
               {"key":"quality","options":[{"value":"high"},{"value":"low"}]},
               {"key":"atmos","default":"auto","options":[{"value":"auto"},{"value":"off"}]}
             ]}
        """
    }
}
