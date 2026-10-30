package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A verification that is abandoned must stop its candidate download at once,
 * not wait for the socket timeout, and must not leave its temporary head
 * behind. Several candidates can also race to create the cache folder.
 */
class HqAudioVerifyCancellationTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = File(
            System.getProperty("java.io.tmpdir"),
            "bitchord-hq-verify-${System.nanoTime()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    @Test
    fun `cancelling stops a stalled download and deletes the temp file`() = runBlocking {
        val requestSeen = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestSeen.countDown()
                release.await(30, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200).setBody("candidate")
            }
        }
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/candidate").toString())
        val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val job = launch(Dispatchers.IO) {
            HqAudioVerify.candidateFeatures(directory, stream, client)
        }
        try {
            assertTrue("request never reached the server", requestSeen.await(5, TimeUnit.SECONDS))
            val stopped = withTimeoutOrNull(2_000L) {
                job.cancelAndJoin()
                true
            }
            assertTrue("cancellation waited for the socket timeout", stopped == true)
            assertTrue(job.isCancelled)
            assertTrue(
                "the temporary candidate file survived cancellation",
                directory.listFiles().orEmpty().isEmpty(),
            )
        } finally {
            release.countDown()
            job.cancel()
        }
    }

    @Test
    fun `a folder another job just made is accepted even when mkdirs fails`() {
        val racing = object : File(File(root, "racing").path) {
            private var checks = 0

            override fun isDirectory(): Boolean {
                checks += 1
                return checks > 1
            }

            override fun mkdirs(): Boolean = false
        }

        assertTrue(HqAudioVerify.ensureCandidateDirectory(racing))
    }

    @Test
    fun `a folder that cannot be made is refused`() {
        val occupied = File(root, "occupied").apply { writeText("not a folder") }

        assertFalse(HqAudioVerify.ensureCandidateDirectory(occupied))
    }

    @Test
    fun `a sleeping candidate gateway is woken once and rested`() = runBlocking {
        for (code in listOf(502, 504)) {
            HqHostTracker.resetForTest()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(code)
            }
            val directory = File(root, "hq_verify_$code").apply { mkdirs() }
            val stream = SourceStream(url = server.url("/candidate").toString())
            val before = server.requestCount

            val result = HqAudioVerify.candidateFeatures(directory, stream, OkHttpClient())

            assertNull(result)
            assertEquals("HTTP $code must be asked exactly once", 1, server.requestCount - before)
            val host = HqHostTracker.hostOf(stream.url)
            assertTrue(
                "HTTP $code must rest the host after the single wake request",
                HqHostTracker.isResting(host),
            )
        }
        HqHostTracker.resetForTest()
    }

    @Test
    fun `a 429 with retry-after is retried once`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            private var calls = 0

            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += 1
                return if (calls == 1) {
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
                } else {
                    MockResponse().setResponseCode(200).setBody("candidate")
                }
            }
        }
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/candidate").toString())

        val result = HqAudioVerify.candidateFeatures(directory, stream, OkHttpClient())

        assertNull(result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a second 429 is not retried and stays unmeasured`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
        }
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/candidate").toString())

        val result = HqAudioVerify.candidateFeatures(directory, stream, OkHttpClient())

        assertNull(result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a track change cancels the retry wait`() = runBlocking {
        val requestSeen = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestSeen.countDown()
                return MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
            }
        }
        val directory = File(root, "hq_verify").apply { mkdirs() }
        val stream = SourceStream(url = server.url("/candidate").toString())

        val job = launch(Dispatchers.IO) {
            HqAudioVerify.candidateFeatures(directory, stream, OkHttpClient())
        }
        try {
            assertTrue("request never reached the server", requestSeen.await(5, TimeUnit.SECONDS))
            Thread.sleep(250)
            assertFalse("the retry wait ended before the track changed", job.isCompleted)

            HqAudioVerify.onPlaybackStarted()

            val stopped = withTimeoutOrNull(2_000L) {
                job.join()
                true
            }
            assertTrue("track change did not cancel the retry wait", stopped == true)
            assertEquals(1, server.requestCount)
        } finally {
            job.cancel()
        }
    }
}
