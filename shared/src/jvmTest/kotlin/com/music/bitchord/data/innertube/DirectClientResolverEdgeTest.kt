package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The anonymous `/player` parser's hostile inputs: missing sections, non-audio
 * formats, absent URLs and the bitrate ceiling's edges.
 */
class DirectClientResolverEdgeTest {

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun root(formats: String, status: String = "\"OK\"") =
        """{"playabilityStatus":{"status":$status},"streamingData":{"adaptiveFormats":[$formats]}}"""

    private val audio251 =
        """{"mimeType":"audio/webm; codecs=\"opus\"","bitrate":152000,"url":"https://a/251"}"""
    private val audio140 =
        """{"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130000,"url":"https://a/140"}"""
    private val audio139 =
        """{"mimeType":"audio/mp4; codecs=\"mp4a.40.5\"","bitrate":49000,"url":"https://a/139"}"""

    @Test
    fun `an OK answer with no streaming data is no answer`() {
        var refused = false
        val result = DirectClientResolver.pickAudio(
            parse("""{"playabilityStatus":{"status":"OK"}}"""),
            maxKbps = 160,
            client = "IOS",
            onRefused = { refused = true },
        )
        assertNull(result)
        assertFalse(refused)
    }

    @Test
    fun `an empty format list is no answer`() {
        var refused = false
        val result = DirectClientResolver.pickAudio(
            parse(root("")),
            maxKbps = 160,
            client = "IOS",
            onRefused = { refused = true },
        )
        assertNull(result)
        assertFalse(refused)
    }

    @Test
    fun `video-only formats are ignored`() {
        val result = DirectClientResolver.pickAudio(
            parse(
                root(
                    """{"mimeType":"video/mp4","bitrate":4000000,"url":"https://v/137"},""" +
                        """{"mimeType":"video/webm","bitrate":3000000,"url":"https://v/248"}""",
                ),
            ),
            maxKbps = 160,
            client = "IOS",
        )
        assertNull(result)
    }

    @Test
    fun `rows without a mime type or a url are skipped`() {
        val result = DirectClientResolver.pickAudio(
            parse(
                root(
                    """{"bitrate":152000,"url":"https://a/no-mime"},""" +
                        """{"mimeType":"audio/webm","bitrate":152000},""" +
                        """{"mimeType":"audio/webm","bitrate":152000,"signatureCipher":"s=x"},""" +
                        audio251,
                ),
            ),
            maxKbps = 160,
            client = "IOS",
        )
        assertEquals("https://a/251", result?.url)
    }

    @Test
    fun `a mime parameter list is stripped from the reported type`() {
        val result = DirectClientResolver.pickAudio(
            parse(root(audio140)),
            maxKbps = 160,
            client = "IOS",
        )
        assertEquals("audio/mp4", result?.mimeType)
    }

    @Test
    fun `the client name is carried through unchanged`() {
        val result = DirectClientResolver.pickAudio(
            parse(root(audio251)),
            maxKbps = 160,
            client = "ANDROID",
        )
        assertEquals("ANDROID", result?.client)
    }

    @Test
    fun `a ceiling of zero or below still takes the smallest rung`() {
        for (ceiling in listOf(0, -1, Int.MIN_VALUE)) {
            val result = DirectClientResolver.pickAudio(
                parse(root("$audio251,$audio140,$audio139")),
                maxKbps = ceiling,
                client = "IOS",
            )
            assertEquals("https://a/139", result?.url, "ceiling=$ceiling")
        }
    }

    @Test
    fun `a ceiling of one still takes the smallest rung`() {
        val result = DirectClientResolver.pickAudio(
            parse(root("$audio251,$audio140,$audio139")),
            maxKbps = 1,
            client = "IOS",
        )
        assertEquals("https://a/139", result?.url)
    }

    @Test
    fun `the highest rung under the ceiling wins`() {
        assertEquals(
            "https://a/251",
            DirectClientResolver.pickAudio(
                parse(root("$audio251,$audio140,$audio139")),
                maxKbps = 160,
                client = "IOS",
            )?.url,
        )
        assertEquals(
            "https://a/140",
            DirectClientResolver.pickAudio(
                parse(root("$audio251,$audio140,$audio139")),
                maxKbps = 151,
                client = "IOS",
            )?.url,
        )
        assertEquals(
            "https://a/139",
            DirectClientResolver.pickAudio(
                parse(root("$audio251,$audio140,$audio139")),
                maxKbps = 129,
                client = "IOS",
            )?.url,
        )
    }

    @Test
    fun `a format with no bitrate counts as zero`() {
        val result = DirectClientResolver.pickAudio(
            parse(
                root(
                    """{"mimeType":"audio/webm","url":"https://a/no-bitrate"}""",
                ),
            ),
            maxKbps = 160,
            client = "IOS",
        )
        assertEquals("https://a/no-bitrate", result?.url)
        assertEquals(0, result?.kbps)
    }

    @Test
    fun `a refusal fires the callback once and returns nothing`() {
        var refused = 0
        val result = DirectClientResolver.pickAudio(
            parse("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"Music Premium"}}"""),
            maxKbps = 160,
            client = "IOS",
            onRefused = { refused++ },
        )
        assertNull(result)
        assertEquals(1, refused)
    }

    @Test
    fun `a missing or malformed playability status is treated as a refusal`() {
        var refused = 0
        val missing = DirectClientResolver.pickAudio(
            parse("""{"streamingData":{"adaptiveFormats":[$audio251]}}"""),
            maxKbps = 160,
            client = "IOS",
            onRefused = { refused++ },
        )
        val wrongShape = DirectClientResolver.pickAudio(
            parse("""{"playabilityStatus":"OK","streamingData":{"adaptiveFormats":[$audio251]}}"""),
            maxKbps = 160,
            client = "IOS",
            onRefused = { refused++ },
        )
        assertNull(missing)
        assertNull(wrongShape)
        assertEquals(2, refused)
    }

    @Test
    fun `an OK status with a premium reason is still OK`() {
        var refused = false
        val result = DirectClientResolver.pickAudio(
            parse(
                """{"playabilityStatus":{"status":"OK","reason":"Music Premium"},""" +
                    """"streamingData":{"adaptiveFormats":[$audio251]}}""",
            ),
            maxKbps = 160,
            client = "IOS",
            onRefused = { refused = true },
        )
        assertEquals("https://a/251", result?.url)
        assertFalse(refused)
    }

    @Test
    fun `an adaptive formats value that is not an array is no answer`() {
        assertNull(
            DirectClientResolver.pickAudio(
                parse("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":{}}}"""),
                maxKbps = 160,
                client = "IOS",
            ),
        )
    }

    @Test
    fun `a tied bitrate keeps the first rung`() {
        val result = DirectClientResolver.pickAudio(
            parse(
                root(
                    """{"mimeType":"audio/webm","bitrate":152000,"url":"https://a/first"},""" +
                        """{"mimeType":"audio/webm","bitrate":152000,"url":"https://a/second"}""",
                ),
            ),
            maxKbps = 160,
            client = "IOS",
        )
        assertEquals("https://a/first", result?.url)
    }

    @Test
    fun `a huge ceiling accepts every rung`() {
        val result = DirectClientResolver.pickAudio(
            parse(root("$audio251,$audio140,$audio139")),
            maxKbps = Int.MAX_VALUE,
            client = "IOS",
        )
        assertEquals("https://a/251", result?.url)
        assertTrue(result!!.kbps > 0)
    }
}
