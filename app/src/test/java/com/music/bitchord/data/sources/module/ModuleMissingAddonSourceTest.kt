package com.music.bitchord.data.sources.module

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [KO] fix1120. Phone 11-20 15:40: two addon modules answered HTTP 404 on every song
 * walk and were downloaded again each time. A gone module is now remembered for the
 * app session, by its download URL.
 */
class ModuleMissingAddonSourceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        KoMissingModules.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        KoMissingModules.resetForTest()
        server.shutdown()
    }

    @Test
    fun `a module that answered 404 is not downloaded again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/gone-flac.js").toString()
        val module = SpineModule(id = "gone-flac", name = "Gone", download = url)
        val manager = ModuleManager()

        val first = manager.loadModule(module)
        val second = manager.loadModule(module)

        assertTrue(first.isFailure)
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("404"))
        // Old code asked the server on every walk.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `only gone answers are remembered, and only for that file`() {
        KoMissingModules.remember("https://a/one.js", "one", 503)
        assertNull("a busy server is asked again", KoMissingModules.knownMissing("https://a/one.js", "one"))

        KoMissingModules.remember("https://a/one.js", "one", 404)
        assertNotNull(KoMissingModules.knownMissing("https://a/one.js", "one"))
        assertNull("a module moved to a new file is tried", KoMissingModules.knownMissing("https://a/two.js", "one"))

        KoMissingModules.remember("https://a/three.js", "three", 410)
        assertNotNull(KoMissingModules.knownMissing("https://a/three.js", "three"))
    }
}
