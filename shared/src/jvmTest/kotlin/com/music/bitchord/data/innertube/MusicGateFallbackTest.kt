package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate is a property of the network, so detection is tested as pure JSON
 * shapes and the per-identity state as a small memory store. Trimmed fixtures
 * mirror the measured Korean responses; the sign-in-only shapes are
 * hand-written against the same renderer names.
 */
class MusicGateFallbackTest {

    private class MemoryStore : MusicGateFallback.Store {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @BeforeTest
    fun setUp() {
        Innertube.cookie = null
        MusicGateFallback.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        MusicGateFallback.resetForTest()
    }

    @Test
    fun `home with no shelves and a Premium upsell is gated`() {
        assertTrue(MusicGateFallback.isGatedHome(json(GATED_HOME)))
        assertFalse(MusicGateFallback.isGatedHome(json(OPEN_HOME)))
    }

    @Test
    fun `moods with no items are gated`() {
        assertTrue(MusicGateFallback.isGatedMoods(json(EMPTY_MOODS)))
        assertFalse(MusicGateFallback.isGatedMoods(json(OPEN_MOODS)))
    }

    @Test
    fun `only an UNPLAYABLE Premium player response is gated`() {
        assertTrue(MusicGateFallback.isGatedPlayer(json(PREMIUM_PLAYER)))
        assertFalse(MusicGateFallback.isGatedPlayer(json(OK_PLAYER)))
    }

    @Test
    fun `a gate is remembered per identity and trusted for a day`() {
        val key = "u:first"
        val at = 1_000_000L
        MusicGateFallback.markGated("test", key = key, now = at)

        assertTrue(MusicGateFallback.isActive(key))
        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state(key))
        assertFalse(MusicGateFallback.shouldTryMusic(key, now = at + 1))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS))

        // The other identity is untouched.
        assertFalse(MusicGateFallback.isActive("u:second"))
        assertTrue(MusicGateFallback.shouldTryMusic("u:second", now = at))

        MusicGateFallback.markOpen(key, now = at + MusicGateFallback.RECHECK_MS)
        assertEquals(MusicGateFallback.State.OPEN, MusicGateFallback.state(key))
        assertTrue(MusicGateFallback.shouldTryMusic(key, now = at + MusicGateFallback.RECHECK_MS))
    }

    @Test
    fun `the last verdict survives a restart`() {
        val store = MemoryStore()
        val key = "u:persisted"
        MusicGateFallback.init(store)
        MusicGateFallback.markGated("test", key = key, now = 42L)

        MusicGateFallback.resetForTest()
        MusicGateFallback.init(store)

        assertTrue(MusicGateFallback.isActive(key))
        assertEquals(MusicGateFallback.State.GATED, MusicGateFallback.state(key))
    }

    @Test
    fun `an observed gated home marks the anonymous session`() {
        // No cookie and no resolved scope: the shared "anonymous" entry.
        MusicGateFallback.observeBrowse("FEmusic_home", json(GATED_HOME))
        assertTrue(MusicGateFallback.isActive("anonymous"))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private companion object {
        const val GATED_HOME = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Get YouTube Music Premium"}]}}}
            ]}}}}]}}}
        """

        const val OPEN_HOME = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Listen again"}]}}},"contents":[]}}
            ]}}}}]}}}
        """

        const val EMPTY_MOODS = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[]}}}}]}}}
        """

        const val OPEN_MOODS = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Pop"}]}}}
            ]}}}}]}}}
        """

        const val PREMIUM_PLAYER = """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"Music Premium 회원만 시청할 수 있는 콘텐츠입니다."}}
        """

        const val OK_PLAYER = """
            {"playabilityStatus":{"status":"OK"},"playbackTracking":{}}
        """
    }
}
