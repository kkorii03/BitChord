package com.music.bitchord.data.innertube

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate's per-identity memory and its re-check windows, including the
 * proxy-aware ten-minute window and the persisted verdicts loaded at start.
 */
class MusicGateFallbackStateTest {

    private class MemoryStore : MusicGateFallback.Store {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private val proxy = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "proxy.example",
        port = 8080,
        scope = ProxyScope.MUSIC_ONLY,
    )

    @BeforeTest
    fun setUp() {
        Innertube.cookie = null
        MusicGateFallback.resetForTest()
        YouTubeProxy.update(ProxyConfig())
    }

    @AfterTest
    fun tearDown() {
        MusicGateFallback.resetForTest()
        YouTubeProxy.update(ProxyConfig())
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `an unknown identity is asked once and is not active`() {
        val key = "u:unknown"
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state(key))
        assertFalse(MusicGateFallback.isActive(key))
        assertTrue(MusicGateFallback.shouldTryMusic(key))
        assertTrue(MusicGateFallback.needsRecheck(key))
    }

    @Test
    fun `an open verdict is never rechecked and never active`() {
        val key = "u:open"
        MusicGateFallback.markOpen(key, now = 5_000L)
        assertEquals(MusicGateFallback.State.OPEN, MusicGateFallback.state(key))
        assertFalse(MusicGateFallback.isActive(key))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = Long.MAX_VALUE))
        assertFalse(MusicGateFallback.needsRecheck(key, now = Long.MAX_VALUE))
    }

    @Test
    fun `a gated verdict is trusted for the daily window without a proxy`() {
        val key = "u:gated"
        val at = 1_000_000L
        MusicGateFallback.markGated("test", key = key, now = at)

        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state(key))
        assertTrue(MusicGateFallback.isActive(key))
        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at))
        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS - 1))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.RECHECK_MS))
    }

    @Test
    fun `a gated verdict with a proxy expires after ten minutes`() {
        val key = "u:gated-proxy"
        val at = 2_000_000L
        YouTubeProxy.update(proxy)
        MusicGateFallback.markGated("test", key = key, now = at)

        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
    }

    @Test
    fun `a fresh gated verdict is not rewritten on every refusal`() {
        val key = "u:fresh"
        val at = 3_000_000L
        MusicGateFallback.markGated("first", key = key, now = at)
        MusicGateFallback.markGated("second", key = key, now = at + 1L)

        assertTrue(
            MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS),
            "the timestamp must still be the first refusal",
        )
    }

    @Test
    fun `a stale gated verdict is refreshed by a new refusal`() {
        val key = "u:refresh"
        val at = 4_000_000L
        MusicGateFallback.markGated("first", key = key, now = at)
        MusicGateFallback.markGated(
            "second",
            key = key,
            now = at + MusicGateFallback.RECHECK_MS,
        )

        assertFalse(
            MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS + 1L),
            "the refreshed timestamp restarts the window",
        )
        assertTrue(
            MusicGateFallback.shouldTryMusic(key, now = at + 2L * MusicGateFallback.RECHECK_MS),
        )
    }

    @Test
    fun `marking open clears the active fallback`() {
        val key = "u:recover"
        MusicGateFallback.markGated("test", key = key, now = 1_000L)
        assertTrue(MusicGateFallback.isActive(key))
        MusicGateFallback.markOpen(key, now = 2_000L)
        assertFalse(MusicGateFallback.isActive(key))
        assertEquals(MusicGateFallback.State.OPEN, MusicGateFallback.state(key))
    }

    @Test
    fun `touch backs an unanswered re-check off for the daily window`() {
        val key = "u:touch"
        val at = 5_000_000L
        MusicGateFallback.touch(key, now = at)
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state(key))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + 1L))
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.RECHECK_MS))
    }

    @Test
    fun `touch with a proxy backs off for the ten-minute window`() {
        val key = "u:touch-proxy"
        val at = 6_000_000L
        YouTubeProxy.update(proxy)
        MusicGateFallback.touch(key, now = at)
        assertFalse(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS - 1))
        assertTrue(MusicGateFallback.needsRecheck(key, now = at + MusicGateFallback.PROXY_RECHECK_MS))
    }

    @Test
    fun `a forced gate covers every identity until it is turned off`() {
        MusicGateFallback.forceForTesting(true)
        for (key in listOf("anonymous", "u:a", "u:b")) {
            assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state(key))
            assertTrue(MusicGateFallback.isActive(key))
            assertFalse(MusicGateFallback.shouldTryMusic(key))
            assertFalse(MusicGateFallback.needsRecheck(key))
        }

        MusicGateFallback.forceForTesting(false)
        assertFalse(MusicGateFallback.isActive("u:a"))
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("u:a"))
    }

    @Test
    fun `a release build cannot force the gate`() {
        MusicGateFallback.setDebugOverridesAllowed(false)
        MusicGateFallback.forceForTesting(true)
        assertFalse(MusicGateFallback.isActive("u:a"))
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("u:a"))
    }

    @Test
    fun `resetAll turns every verdict into unknown and asks again`() {
        val store = MemoryStore()
        MusicGateFallback.init(store)
        MusicGateFallback.markGated("test", key = "u:a", now = 10L)
        MusicGateFallback.markOpen(key = "u:b", now = 10L)

        MusicGateFallback.resetAll()

        for (key in listOf("u:a", "u:b")) {
            assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state(key))
            assertFalse(MusicGateFallback.isActive(key))
            assertTrue(MusicGateFallback.shouldTryMusic(key))
        }
        assertTrue(store.values["music_gate_fallback_v1"]?.contains("UNKNOWN") == true)
    }

    @Test
    fun `resetAll with no entries does not write a preference`() {
        val store = MemoryStore()
        MusicGateFallback.init(store)
        MusicGateFallback.resetAll()
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `malformed persisted state is ignored instead of crashing`() {
        val store = MemoryStore()
        store.values["music_gate_fallback_v1"] = "{not json"
        MusicGateFallback.init(store)
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("u:a"))
        assertTrue(MusicGateFallback.shouldTryMusic("u:a"))
    }

    @Test
    fun `an unknown persisted state name reads as unknown`() {
        val store = MemoryStore()
        store.values["music_gate_fallback_v1"] =
            """{"version":1,"entries":{"u:a":{"state":"BROKEN","at":5}}}"""
        MusicGateFallback.init(store)
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("u:a"))
        assertTrue(
            MusicGateFallback.needsRecheck(
                "u:a",
                now = 5L + MusicGateFallback.RECHECK_MS,
            ),
        )
    }

    @Test
    fun `an open verdict survives a restart`() {
        val store = MemoryStore()
        MusicGateFallback.init(store)
        MusicGateFallback.markOpen(key = "u:open", now = 42L)

        MusicGateFallback.resetForTest()
        MusicGateFallback.init(store)

        assertEquals(MusicGateFallback.State.OPEN, MusicGateFallback.state("u:open"))
        assertFalse(MusicGateFallback.isActive("u:open"))
        assertFalse(MusicGateFallback.needsRecheck("u:open", now = Long.MAX_VALUE))
    }

    @Test
    fun `an unknown browse id changes nothing`() {
        assertEquals(
            MusicGateFallback.Signal.NONE,
            MusicGateFallback.observeBrowse("FEmusic_library", json("{}")),
        )
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("anonymous"))
    }

    @Test
    fun `a gated and then open home is observed on the anonymous identity`() {
        val gated = json(
            """
            {"contents":[{"musicDescriptionShelfRenderer":
              {"description":{"runs":[{"text":"Get YouTube Music Premium"}]}}}]}
            """.trimIndent(),
        )
        val open = json(
            """{"contents":[{"musicCarouselShelfRenderer":{"contents":[1]}}]}""",
        )

        assertEquals(MusicGateFallback.Signal.GATED, MusicGateFallback.observeBrowse("FEmusic_home", gated))
        assertTrue(MusicGateFallback.isActive("anonymous"))

        assertEquals(MusicGateFallback.Signal.OPEN, MusicGateFallback.observeBrowse("FEmusic_home", open))
        assertFalse(MusicGateFallback.isActive("anonymous"))
    }

    @Test
    fun `moods are observed as gated and open too`() {
        val gated = json(
            """{"contents":[{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"프리미엄"}]}}}]}""",
        )
        val open = json("""{"contents":[{"musicNavigationButtonRenderer":{"buttonText":"Pop"}}]}""")

        assertEquals(
            MusicGateFallback.Signal.GATED,
            MusicGateFallback.observeBrowse("FEmusic_moods_and_genres", gated),
        )
        assertEquals(
            MusicGateFallback.Signal.OPEN,
            MusicGateFallback.observeBrowse("FEmusic_moods_and_genres", open),
        )
    }

    @Test
    fun `only a premium player response is a gate signal`() {
        assertEquals(
            MusicGateFallback.Signal.NONE,
            MusicGateFallback.observePlayer(json("""{"playabilityStatus":{"status":"OK"}}""")),
        )
        assertEquals(
            MusicGateFallback.Signal.GATED,
            MusicGateFallback.observePlayer(
                json("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Music Premium"}}"""),
            ),
        )
    }

    @Test
    fun `a premium stream failure marks the gate`() {
        MusicGateFallback.observePremiumFailure("YoutubeMusicPremiumContentException: nope")
        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state("anonymous"))

        MusicGateFallback.resetForTest()
        MusicGateFallback.observePremiumFailure("video unavailable")
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("anonymous"))
        MusicGateFallback.observePremiumFailure(null)
        assertEquals(MusicGateFallback.State.UNKNOWN, MusicGateFallback.state("anonymous"))
    }
}
