package com.music.bitchord.data.lyrics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A cancelled next-prep bundle must free its Deepgram request at once: the
 * blocking execute had a 240 s call timeout, so without cancelling the call a
 * cancelled song check kept paying for its upload long after the row was gone
 * (11-02: ~70 s for a prepared song).
 */
class DeepgramCancellationTest {

    @Test
    fun cancellingListenCancelsTheHttpRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        val requestSeen = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestSeen.countDown()
                release.await(30, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
        Deepgram.apiKey = "test-key"
        Deepgram.listenEndpoint = server.url("/listen").toString()
        val job = launch(Dispatchers.IO) {
            Deepgram.listen(ByteArray(16), "en", Deepgram.MODEL_WHISPER)
        }
        try {
            assertTrue(requestSeen.await(5, TimeUnit.SECONDS), "request never reached the server")
            val stopped = withTimeoutOrNull(2_000L) {
                job.cancelAndJoin()
                true
            }
            assertTrue(stopped == true, "cancellation waited for the HTTP timeout")
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            Deepgram.apiKey = ""
            Deepgram.listenEndpoint = "https://api.deepgram.com/v1/listen"
            server.shutdown()
        }
    }
}
