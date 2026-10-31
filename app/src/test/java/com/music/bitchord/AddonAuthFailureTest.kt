package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.addon.AddonAuthException
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A refused addon credential is not a catalogue miss.
 *
 * On 10-31 an addon answered 401 on every search. [AddonSource] turned that
 * into an empty list, [SourceResolver] remembered it as a confirmed "nobody
 * has it", and the hq-search line blamed the title (`verdict=title`). These
 * tests pin the distinction: 401/402/403 become `verdict=auth`, are never
 * memoised, still follow the existing rest rule, and the listener-facing
 * answer names the source instead of claiming no better copy exists.
 */
class AddonAuthFailureTest {

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.offlineCheck = { false }
        HqHostTracker.resetForTest()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.offlineCheck = { AudioCache.isOffline() }
        HqHostTracker.resetForTest()
    }

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    private fun config(id: String = "Qobuz") = SourceConfig(
        id = id,
        kind = SourceKind.ADDON,
        label = id,
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun refuseAllWith(code: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(paths) { paths += request.requestUrl?.encodedPath.orEmpty() }
                return MockResponse().setResponseCode(code)
            }
        }
    }

    @Test
    fun `a 401 402 or 403 addon search is an auth failure, not a parse or transport failure`() = runBlocking {
        for (code in listOf(401, 402, 403)) {
            refuseAllWith(code)
            val client = AddonClient(server.url("/").toString())

            val result = client.search("Flowers", AddonClient.TIER_LOSSLESS)

            assertTrue(result.isFailure)
            assertTrue(
                "$code must stay distinguishable from a 5xx and from a missing track",
                result.exceptionOrNull() is AddonAuthException,
            )
            assertEquals(code, (result.exceptionOrNull() as AddonAuthException).status)
        }
    }

    @Test
    fun `a 401 search is verdict auth, is not cached, and follows the rest rule`() = runBlocking {
        refuseAllWith(401)
        val source = AddonSource(config())
        val first = SourceResolver.SearchReport()

        assertNull(
            SourceResolver.bestAcross(
                listOf(source),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                report = first,
            ),
        )

        assertEquals("auth", first.outcomes().single().verdict)
        assertFalse("a refused credential cannot confirm a miss", first.allConclusive)
        assertEquals(listOf("Qobuz"), first.authSourceNames)
        assertEquals(1L, SourceResolver.SourceCooldown.failureMark("Qobuz"))

        val searchesBefore = searchRequests()
        val second = SourceResolver.SearchReport()
        assertNull(
            SourceResolver.bestAcross(
                listOf(source),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                manual = true,
                report = second,
            ),
        )
        assertEquals("the refused empty answer was cached as final", searchesBefore + 1, searchRequests())
        assertEquals("auth", second.outcomes().single().verdict)
        assertEquals(2L, SourceResolver.SourceCooldown.failureMark("Qobuz"))
        assertTrue(
            "a source refusing its credential follows the existing rest rule",
            SourceResolver.SourceCooldown.isResting("Qobuz"),
        )
    }

    @Test
    fun `the sources screen names the refused credential`() = runBlocking {
        refuseAllWith(401)

        val health = AddonSource(config("Qobuz")).health()

        assertTrue("expected Rejected, got $health", health is SourceHealth.Rejected)
        assertTrue(
            "the reason must say the addon needs reconnecting",
            (health as SourceHealth.Rejected).reason.contains("인증이 거부되었습니다"),
        )
    }

    @Test
    fun `manual upgrades get twelve seconds per source while automatic keeps four`() = runBlocking {
        assertEquals(4_000L, SourceResolver.sourceLookupTimeoutMs(manual = false))
        assertEquals(12_000L, SourceResolver.sourceLookupTimeoutMs(manual = true))

        val slow = DelayedSource(delayMs = 300)
        assertNotNull(
            "a manual request must wait past the automatic ceiling",
            SourceResolver.bestAcross(
                listOf(slow),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                sourceTimeoutMs = 100L,
                manual = true,
            ),
        )

        SourceResolver.clearSearchAnswers()
        val automatic = DelayedSource(delayMs = 300)
        assertNull(
            "the automatic path keeps its configured ceiling",
            SourceResolver.bestAcross(
                listOf(automatic),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                sourceTimeoutMs = 100L,
            ),
        )
    }

    @Test
    fun `the auth answer is localised in both resource sets`() {
        val root = File(".").canonicalFile
        val res = if (File(root, "app/src/main/res").exists()) {
            File(root, "app/src/main/res")
        } else {
            File(root, "src/main/res")
        }
        val english = File(res, "values/strings.xml").readText()
        val korean = File(res, "values-ko/strings.xml").readText()
        assertTrue(english.contains("quality_upgrade_addon_auth"))
        assertTrue(english.contains("authentication was refused"))
        assertTrue(korean.contains("quality_upgrade_addon_auth"))
        assertTrue(korean.contains("인증이 거부되었습니다"))
    }

    private fun searchRequests(): Int = synchronized(paths) {
        paths.count { it == "/search" }
    }

    private class DelayedSource(private val delayMs: Long) : MusicSource {
        override val configId: String get() = "Qobuz"
        override val displayName: String get() = "Qobuz"
        override val kind: SourceKind get() = SourceKind.ADDON

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            delay(delayMs)
            return listOf(
                Song(
                    videoId = "flowers-1",
                    title = "Flowers",
                    artist = "Miley Cyrus",
                    thumbnailUrl = null,
                    durationText = "3:20",
                ),
            )
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://qobuz/$trackId", format = StreamFormat("flac"))
    }
}
