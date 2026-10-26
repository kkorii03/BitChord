package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure detection shapes for the Korea/no-Premium gate. A false positive pins
 * an account to the youtube.com fallback for a day, so an empty shelf or an
 * empty mood grid is only evidence together with the Premium upsell.
 */
class MusicGateFallbackDetectionTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `only shelves with rows count as home content`() {
        val empty = json(
            """{"musicCarouselShelfRenderer":{"contents":[]}}""",
        )
        val filled = json(
            """{"musicCarouselShelfRenderer":{"contents":[{"row":1}]}}""",
        )
        val wrongShape = json(
            """{"musicCarouselShelfRenderer":{"contents":{}}}""",
        )
        assertEquals(0, MusicGateFallback.musicHomeShelfCount(empty))
        assertEquals(1, MusicGateFallback.musicHomeShelfCount(filled))
        assertEquals(0, MusicGateFallback.musicHomeShelfCount(wrongShape))
    }

    @Test
    fun `shelves are counted through arrays and nesting`() {
        val root = json(
            """
            {"contents":[
              {"musicCarouselShelfRenderer":{"contents":[1]}},
              {"section":{"musicShelfRenderer":{"contents":[1,2]}}},
              {"musicCardShelfRenderer":{"contents":[1]}},
              {"musicCarouselShelfRenderer":{"contents":[]}}
            ]}
            """.trimIndent(),
        )
        assertEquals(3, MusicGateFallback.musicHomeShelfCount(root))
    }

    @Test
    fun `mood items count navigation buttons and filled shelves`() {
        val root = json(
            """
            {"contents":[
              {"musicNavigationButtonRenderer":{"buttonText":"Pop"}},
              {"musicCarouselShelfRenderer":{"contents":[1]}},
              {"musicCarouselShelfRenderer":{"contents":[]}}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, MusicGateFallback.musicMoodItemCount(root))
        assertEquals(0, MusicGateFallback.musicMoodItemCount(json("{}")))
    }

    @Test
    fun `the premium upsell is recognised in english and korean`() {
        assertTrue(MusicGateFallback.hasPremiumUpsell(json("""{"text":"YouTube Music Premium"}""")))
        assertTrue(MusicGateFallback.hasPremiumUpsell(json("""{"text":"Music Premium"}""")))
        assertTrue(MusicGateFallback.hasPremiumUpsell(json("""{"text":"프리미엄 회원"}""")))
        assertTrue(MusicGateFallback.hasPremiumUpsell(json("""{"text":"Premium membership"}""")))
        assertTrue(MusicGateFallback.hasPremiumUpsell(json("""{"text":"premium 구독"}""")))
        assertFalse(MusicGateFallback.hasPremiumUpsell(json("""{"text":"Premium sound quality"}""")))
        assertFalse(MusicGateFallback.hasPremiumUpsell(json("""{"text":"Free tier"}""")))
        assertFalse(MusicGateFallback.hasPremiumUpsell(json("{}")))
    }

    @Test
    fun `an empty home is gated only with the upsell`() {
        val gated = json(
            """
            {"contents":[{"musicDescriptionShelfRenderer":
              {"description":{"runs":[{"text":"Get YouTube Music Premium"}]}}}]}
            """.trimIndent(),
        )
        val emptyWithoutUpsell = json("""{"contents":[]}""")
        assertTrue(MusicGateFallback.isGatedHome(gated))
        assertFalse(MusicGateFallback.isGatedHome(emptyWithoutUpsell))
    }

    @Test
    fun `a home with content is not gated even with an upsell`() {
        val root = json(
            """
            {"contents":[
              {"musicCarouselShelfRenderer":{"contents":[{"row":1}]}},
              {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Music Premium"}]}}}
            ]}
            """.trimIndent(),
        )
        assertFalse(MusicGateFallback.isGatedHome(root))
    }

    @Test
    fun `an empty mood grid is gated only with the upsell`() {
        val gated = json(
            """
            {"contents":[
              {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"프리미엄"}]}}}
            ]}
            """.trimIndent(),
        )
        val emptyWithoutUpsell = json("""{"contents":[]}""")
        assertTrue(MusicGateFallback.isGatedMoods(gated))
        assertFalse(MusicGateFallback.isGatedMoods(emptyWithoutUpsell))
    }

    @Test
    fun `a mood grid with a navigation button is not gated`() {
        val root = json(
            """
            {"contents":[
              {"musicNavigationButtonRenderer":{"buttonText":"Pop"}},
              {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Music Premium"}]}}}
            ]}
            """.trimIndent(),
        )
        assertFalse(MusicGateFallback.isGatedMoods(root))
    }

    @Test
    fun `a mood grid with a filled shelf is not gated`() {
        val root = json(
            """
            {"contents":[
              {"musicCarouselShelfRenderer":{"contents":[1]}},
              {"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Music Premium"}]}}}
            ]}
            """.trimIndent(),
        )
        assertFalse(MusicGateFallback.isGatedMoods(root))
    }

    @Test
    fun `an UNPLAYABLE player with a premium reason is gated`() {
        assertTrue(
            MusicGateFallback.isGatedPlayer(
                json("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Music Premium"}}"""),
            ),
        )
        assertTrue(
            MusicGateFallback.isGatedPlayer(
                json("""{"playabilityStatus":{"status":"UNPLAYABLE","subreason":"프리미엄 회원만"}}"""),
            ),
        )
        assertTrue(
            MusicGateFallback.isGatedPlayer(
                json(
                    """{"a":[{"playabilityStatus":{"status":"UNPLAYABLE","x":{"y":"YoutubeMusicPremiumContentException"}}}]}""",
                ),
            ),
        )
    }

    @Test
    fun `an OK player with premium text is not gated`() {
        assertFalse(
            MusicGateFallback.isGatedPlayer(
                json("""{"playabilityStatus":{"status":"OK","reason":"Music Premium"}}"""),
            ),
        )
    }

    @Test
    fun `an UNPLAYABLE player without the premium signal is not gated`() {
        assertFalse(
            MusicGateFallback.isGatedPlayer(
                json("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"}}"""),
            ),
        )
        assertFalse(
            MusicGateFallback.isGatedPlayer(
                json("""{"playabilityStatus":{"status":"UNPLAYABLE"}}"""),
            ),
        )
        assertFalse(MusicGateFallback.isGatedPlayer(json("{}")))
    }

    @Test
    fun `the playability status is case-sensitive like the api`() {
        assertFalse(
            MusicGateFallback.isGatedPlayer(
                json("""{"playabilityStatus":{"status":"unplayable","reason":"Music Premium"}}"""),
            ),
        )
    }

    @Test
    fun `the fallback truth table keeps every old term`() {
        for (proxyUsable in listOf(true, false)) {
            for (proxyUnreachable in listOf(true, false)) {
                for (gateActive in listOf(true, false)) {
                    for (gateSignal in listOf(true, false)) {
                        val expected = when {
                            proxyUsable && proxyUnreachable -> true
                            proxyUsable -> gateActive && gateSignal
                            else -> gateActive
                        }
                        assertEquals(
                            expected,
                            MusicGateFallback.shouldUseYouTubeFallback(
                                proxyUsable = proxyUsable,
                                proxyUnreachable = proxyUnreachable,
                                gateActive = gateActive,
                                gateSignal = gateSignal,
                            ),
                            "usable=$proxyUsable unreachable=$proxyUnreachable " +
                                "active=$gateActive signal=$gateSignal",
                        )
                    }
                }
            }
        }
    }
}
