package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroqWhisperTest {

    private val server = MockWebServer()

    @AfterTest
    fun stop() {
        GroqWhisper.apiKey = ""
        server.shutdown()
    }

    private fun pointAtServer() {
        server.start()
        GroqWhisper.apiKey = "test-key"
        GroqWhisper.endpoint = server.url("/transcriptions").toString()
    }

    @Test
    fun wordsAndHeardLengthAreReadLikeDeepgrams() = runBlocking {
        pointAtServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"duration":86.79,"words":[{"word":" one","start":1.5,"end":1.9},{"word":" ","start":2.0,"end":2.1},{"word":"two","start":2.25}]}""",
            ),
        )

        val answer = GroqWhisper.listen(ByteArray(16), "ko", "audio/webm")

        assertEquals(200, answer.httpCode)
        assertEquals(86_790L, answer.audioMs)
        assertEquals(listOf("one", "two"), answer.words?.map { it.text })
        assertEquals(1_500L, answer.words?.first()?.startMs)
        assertEquals(2_250L, answer.words?.last()?.endMs)
    }

    @Test
    fun theRequestNamesTheContainerAndTheLanguageAndAsksForWordTimes() = runBlocking {
        pointAtServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"duration":1.0,"words":[]}"""))

        GroqWhisper.listen(ByteArray(16), "ko", "audio/webm")

        val request = server.takeRequest()
        val form = request.body.readUtf8()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue("filename=\"audio.webm\"" in form)
        assertTrue("timestamp_granularities[]" in form && "\r\n\r\nword\r\n" in form)
        assertTrue("name=\"language\"" in form && "\r\n\r\nko\r\n" in form)
    }

    @Test
    fun aDetectedLanguageIsLeftForWhisperToWorkOut() = runBlocking {
        pointAtServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"duration":1.0,"words":[]}"""))

        GroqWhisper.listen(ByteArray(16), "multi", "audio/wav")

        assertTrue("name=\"language\"" !in server.takeRequest().body.readUtf8())
    }

    @Test
    fun aRefusalIsNoWordsWithItsCode() = runBlocking {
        pointAtServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

        val answer = GroqWhisper.listen(ByteArray(16), "en", "audio/mp4")

        assertNull(answer.words)
        assertEquals(429, answer.httpCode)
    }

    @Test
    fun noKeyAsksNothing() = runBlocking {
        pointAtServer()
        GroqWhisper.apiKey = " "

        assertNull(GroqWhisper.listen(ByteArray(16), "en", "audio/wav").words)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun eachContainerGetsAFileNameGroqCanRead() {
        assertEquals("audio.webm", GroqWhisper.fileName("audio/webm"))
        assertEquals("audio.m4a", GroqWhisper.fileName("audio/mp4"))
        assertEquals("audio.wav", GroqWhisper.fileName("audio/wav"))
    }
}
