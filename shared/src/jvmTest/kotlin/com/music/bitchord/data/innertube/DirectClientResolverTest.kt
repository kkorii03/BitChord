package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The anonymous IOS/ANDROID `/player` answer is read the way the Korean no-Premium path needs. */
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
}
