package com.music.bitchord.data.innertube

import com.music.bitchord.data.innertube.potoken.PoTokenPageAction
import com.music.bitchord.data.innertube.potoken.poTokenPageAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The anonymous IOS/ANDROID `/player` answer is read the way the Korean
 * no-Premium path needs, and [ResolutionOrder]'s learning rule decides which
 * client path is asked first on the next track.
 */
class DirectClientResolverTest {

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private val ok = """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{"adaptiveFormats":[
           {"itag":137,"mimeType":"video/mp4; codecs=\"avc1\"","bitrate":4000000,"url":"https://v/137"},
           {"itag":139,"mimeType":"audio/mp4; codecs=\"mp4a.40.5\"","bitrate":49000,"url":"https://a/139"},
           {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"url":"https://a/140"},
           {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":152000,"url":"https://a/251"},
           {"itag":250,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":70000,"signatureCipher":"s=x"}
         ]}}
    """.trimIndent()

    @Test
    fun picksTheBestDirectAudioUnderTheCeiling() {
        val found = DirectClientResolver.pickAudio(parse(ok), maxKbps = 160, client = "IOS")!!
        assertEquals("https://a/251", found.url)
        assertEquals(152, found.kbps)
        assertEquals("audio/webm", found.mimeType)
        assertEquals("IOS", found.client)
    }

    @Test
    fun aLowCeilingTakesTheBestThatFits() {
        val found = DirectClientResolver.pickAudio(parse(ok), maxKbps = 128, client = "IOS")!!
        assertEquals("https://a/139", found.url)
    }

    @Test
    fun nothingUnderTheCeilingFallsBackToTheSmallest() {
        val found = DirectClientResolver.pickAudio(parse(ok), maxKbps = 10, client = "IOS")!!
        assertEquals("https://a/139", found.url)
    }

    @Test
    fun aRefusalIsNoAnswer() {
        val gated = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Music Premium"}}"""
        assertNull(DirectClientResolver.pickAudio(parse(gated), maxKbps = 160, client = "IOS"))
    }

    @Test
    fun cipheredOnlyIsNoAnswer() {
        val ciphered = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
              {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":152000,"signatureCipher":"s=x"}]}}
        """.trimIndent()
        assertNull(DirectClientResolver.pickAudio(parse(ciphered), maxKbps = 160, client = "ANDROID"))
    }

    @Test
    fun malformedFormatRowsAreSkippedWithoutThrowing() {
        val mixed = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
              null,
              "not a format",
              {"mimeType":5,"bitrate":"high","url":42},
              {"mimeType":"audio/webm; codecs=\"opus\"","bitrate":152000,"url":"https://a/251"}]}}
        """.trimIndent()

        val found = DirectClientResolver.pickAudio(parse(mixed), maxKbps = 160, client = "IOS")!!
        assertEquals("https://a/251", found.url)
    }

    @Test
    fun unexpectedRootShapesAreNoAnswer() {
        val flatStatus = """{"playabilityStatus":"OK","streamingData":{"adaptiveFormats":[]}}"""
        assertNull(DirectClientResolver.pickAudio(parse(flatStatus), maxKbps = 160, client = "IOS"))

        val flatFormats = """
            {"playabilityStatus":{"status":"OK"},"streamingData":[
              {"mimeType":"audio/webm","bitrate":152000,"url":"https://a/251"}]}
        """.trimIndent()
        assertNull(DirectClientResolver.pickAudio(parse(flatFormats), maxKbps = 160, client = "IOS"))
    }

    // ---- ResolutionOrder: which path the next track asks first ----------------

    private class MemoryStore : ResolutionOrder.Store {
        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()

        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String): Long? = longs[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    private var clock = 1_000_000L

    private fun order(store: ResolutionOrder.Store? = null) = ResolutionOrder(store) { clock }

    @Test
    fun `no learned path keeps the measured default order`() {
        val order = order()
        assertNull(order.preferred())
        assertTrue(order.directAllowed())
    }

    @Test
    fun `the path that served is tried first`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        assertEquals("itx:WEB_CREATOR", order.preferred())
    }

    @Test
    fun `two consecutive misses forget the winner`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteRefused(ResolutionOrder.itx("WEB_CREATOR"))
        assertEquals("itx:WEB_CREATOR", order.preferred())
        order.noteRefused(ResolutionOrder.itx("WEB_CREATOR"))
        assertNull(order.preferred())
    }

    @Test
    fun `a success between misses keeps the winner`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteRefused(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteRefused(ResolutionOrder.itx("WEB_CREATOR"))
        assertEquals("itx:WEB_CREATOR", order.preferred())
    }

    @Test
    fun `a different path that serves replaces the winner`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteServed(ResolutionOrder.DIRECT)
        assertEquals(ResolutionOrder.DIRECT, order.preferred())
    }

    @Test
    fun `extraction is never learned as the path to lead with`() {
        val order = order()
        order.noteServed(ResolutionOrder.NEW_PIPE)
        assertNull(order.preferred())
    }

    @Test
    fun `a learned winner expires after a day`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        clock += ResolutionOrder.WINNER_TTL_MS - 1
        assertEquals("itx:WEB_CREATOR", order.preferred())
        clock += 2
        assertNull(order.preferred())
    }

    @Test
    fun `the winner survives a new instance for a day`() {
        val store = MemoryStore()
        order(store).noteServed(ResolutionOrder.itx("WEB_CREATOR"))

        clock += 60_000L
        assertEquals("itx:WEB_CREATOR", order(store).preferred())

        clock += ResolutionOrder.WINNER_TTL_MS
        assertNull(order(store).preferred())
    }

    @Test
    fun `direct is benched after five refusals in six tracks`() {
        val order = order()
        order.noteDirectServed()
        repeat(5) { order.noteDirectRefused() }
        assertFalse(order.directAllowed())
    }

    @Test
    fun `four refusals in six tracks do not bench direct`() {
        val order = order()
        repeat(2) { order.noteDirectServed() }
        repeat(4) { order.noteDirectRefused() }
        assertTrue(order.directAllowed())
    }

    @Test
    fun `the direct bench expires after thirty minutes`() {
        val order = order()
        order.noteDirectServed()
        repeat(5) { order.noteDirectRefused() }
        assertFalse(order.directAllowed())
        clock += ResolutionOrder.DIRECT_BENCH_MS
        assertTrue(order.directAllowed())
    }

    @Test
    fun `a benched direct winner does not lead the order`() {
        val order = order()
        order.noteServed(ResolutionOrder.DIRECT)
        order.noteDirectServed()
        repeat(5) { order.noteDirectRefused() }
        assertNull(order.preferred())
        clock += ResolutionOrder.DIRECT_BENCH_MS
        assertEquals(ResolutionOrder.DIRECT, order.preferred())
    }

    /**
     * The cold-start fix: a live BotGuard page is reused and re-minted for a new
     * visitor id instead of paying Google's Create/GenerateIT round trips again.
     */
    @Test
    fun `a live page is kept for the same visitor`() {
        assertEquals(
            PoTokenPageAction.Keep,
            poTokenPageAction(forceRecreate = false, hasPage = true, expired = false, dead = false, sessionChanged = false),
        )
    }

    @Test
    fun `a changed visitor re-mints on the live page`() {
        assertEquals(
            PoTokenPageAction.Retoken,
            poTokenPageAction(forceRecreate = false, hasPage = true, expired = false, dead = false, sessionChanged = true),
        )
    }

    @Test
    fun `a missing expired or dead page is rebuilt`() {
        assertEquals(
            PoTokenPageAction.Create,
            poTokenPageAction(forceRecreate = false, hasPage = false, expired = false, dead = false, sessionChanged = false),
        )
        assertEquals(
            PoTokenPageAction.Create,
            poTokenPageAction(forceRecreate = false, hasPage = true, expired = true, dead = false, sessionChanged = true),
        )
        assertEquals(
            PoTokenPageAction.Create,
            poTokenPageAction(forceRecreate = false, hasPage = true, expired = false, dead = true, sessionChanged = false),
        )
        assertEquals(
            PoTokenPageAction.Create,
            poTokenPageAction(forceRecreate = true, hasPage = true, expired = false, dead = false, sessionChanged = false),
        )
    }
}
