package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A logged URL must name the request without carrying its signed query or any
 * credential a caller happened to put in it.
 */
class LogUrlTest {

    @Test
    fun `a signed stream URL keeps the host and path only`() {
        val logged = LogUrl.of(
            "https://rr3---sn-x.googlevideo.com/videoplayback?expire=1&sig=secret&cpn=visitor",
        )

        assertEquals("https://rr3---sn-x.googlevideo.com/videoplayback", logged)
        assertFalse(logged.contains("sig=secret"))
        assertFalse(logged.contains("cpn=visitor"))
    }

    @Test
    fun `an extractor URL drops its query and fragment`() {
        val logged = LogUrl.of(
            "https://www.youtube.com/watch?v=abc123&bpctr=1#player",
        )

        assertEquals("https://www.youtube.com/watch", logged)
    }

    @Test
    fun `a URL without a query is unchanged`() {
        assertEquals(
            "https://music.youtube.com/youtubei/v1/browse",
            LogUrl.of("https://music.youtube.com/youtubei/v1/browse"),
        )
    }

    @Test
    fun `credentials in the authority are not logged`() {
        val logged = LogUrl.of("https://listener:super-secret@proxy.example:8443/index.js?token=abc")

        assertEquals("https://proxy.example/index.js", logged)
        assertFalse(logged.contains("listener"))
        assertFalse(logged.contains("super-secret"))
        assertFalse(logged.contains("token=abc"))
    }

    @Test
    fun `an unparseable value is named rather than echoed`() {
        assertEquals("[invalid-url]", LogUrl.of("not a url?query=secret"))
    }
}
