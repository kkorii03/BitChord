package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Diagnostic URLs must name the request without carrying signed parameters,
 * credentials or fragments. These are the shapes a stream or extractor URL
 * arrives in when it is pasted into a log.
 */
class LogUrlEdgeTest {

    @Test
    fun `blank and unparseable values are named not echoed`() {
        assertEquals("[invalid-url]", LogUrl.of(""))
        assertEquals("[invalid-url]", LogUrl.of("   "))
        assertEquals("[invalid-url]", LogUrl.of("not a url?query=secret"))
        assertEquals("[invalid-url]", LogUrl.of("https://"))
        assertEquals("[invalid-url]", LogUrl.of("://example.com/path?x=1"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            "https://music.youtube.com/youtubei/v1/browse",
            LogUrl.of("  https://music.youtube.com/youtubei/v1/browse  "),
        )
    }

    @Test
    fun `the scheme and host are canonicalized`() {
        assertEquals(
            "https://example.com/Path/To/File",
            LogUrl.of("HTTPS://EXAMPLE.COM/Path/To/File"),
        )
    }

    @Test
    fun `a URL with no path logs the root path`() {
        assertEquals("https://example.com/", LogUrl.of("https://example.com"))
        assertEquals("https://example.com/", LogUrl.of("https://example.com?x=1"))
        assertEquals("https://example.com/", LogUrl.of("https://example.com#fragment"))
    }

    @Test
    fun `the port and user info are not logged`() {
        val logged = LogUrl.of("https://listener:secret@proxy.example:8443/index.js?token=abc")
        assertEquals("https://proxy.example/index.js", logged)
        assertFalse(logged.contains("8443"))
        assertFalse(logged.contains("listener"))
        assertFalse(logged.contains("secret"))
        assertFalse(logged.contains("token"))
    }

    @Test
    fun `an encoded path is kept encoded`() {
        assertEquals(
            "https://example.com/a%20b/%EC%84%9C",
            LogUrl.of("https://example.com/a%20b/%EC%84%9C?q=1"),
        )
    }

    @Test
    fun `the fragment is dropped even without a query`() {
        assertEquals(
            "https://www.youtube.com/watch",
            LogUrl.of("https://www.youtube.com/watch#player"),
        )
    }

    @Test
    fun `every signed query parameter is dropped`() {
        val logged = LogUrl.of(
            "https://rr1.googlevideo.com/videoplayback?expire=1&ei=x&ip=1.2.3.4" +
                "&id=abc&itag=251&source=youtube&requiressl=yes&mh=1&mm=2" +
                "&mn=3&ms=4&mv=5&mvi=6&pl=7&initcwndbps=8&spc=9&vprv=10" +
                "&svpuc=11&mime=audio%2Fwebm&ns=12&gir=13&clen=14&dur=15" +
                "&lmt=16&mt=17&fvip=18&keepalive=yes&fexp=19&c=IOS&txp=20" +
                "&n=21&sparams=22&sig=23&lsparams=24&lsig=25&cpn=26",
        )
        assertEquals("https://rr1.googlevideo.com/videoplayback", logged)
        for (secret in listOf("sig=23", "lsig=25", "cpn=26", "expire=1", "ip=1.2.3.4")) {
            assertFalse(logged.contains(secret), secret)
        }
    }

    @Test
    fun `a plus in the query is not part of the logged URL`() {
        assertEquals(
            "https://example.com/search",
            LogUrl.of("https://example.com/search?q=a+b"),
        )
    }
}
