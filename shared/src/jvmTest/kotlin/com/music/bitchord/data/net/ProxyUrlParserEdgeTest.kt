package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single-string form a debug launch extra carries. A half-parsed URL would
 * configure a proxy that silently does nothing, so every malformed shape must
 * be rejected rather than guessed at.
 */
class ProxyUrlParserEdgeTest {

    private fun parse(raw: String?) = ProxyUrlParser.parse(raw)

    @Test
    fun `null blank and whitespace are not URLs`() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("\t\n"))
    }

    @Test
    fun `the scheme is case-insensitive and https is the http transport`() {
        assertEquals(ProxyMode.HTTP, parse("HTTP://proxy.example:8080")?.mode)
        assertEquals(ProxyMode.HTTP, parse("Https://proxy.example:8080")?.mode)
        assertEquals(ProxyMode.SOCKS5, parse("SOCKS5://proxy.example:1080")?.mode)
        assertEquals(ProxyMode.SOCKS5, parse("Socks5h://proxy.example:1080")?.mode)
    }

    @Test
    fun `an unsupported or missing scheme is rejected`() {
        assertNull(parse("ftp://proxy.example:21"))
        assertNull(parse("proxy.example:8080"))
        assertNull(parse("://proxy.example:8080"))
        assertNull(parse("http:/proxy.example:8080"))
        assertNull(parse("http://"))
        assertNull(parse("http:///proxy.example"))
    }

    @Test
    fun `surrounding whitespace is trimmed before parsing`() {
        val config = parse("  http://proxy.example:8080  ")
        assertNotNull(config)
        assertEquals("proxy.example", config.host)
        assertEquals(8080, config.port)
    }

    @Test
    fun `trailing slashes are not part of the authority`() {
        for (raw in listOf(
            "http://proxy.example:8080/",
            "http://proxy.example:8080//",
            "http://proxy.example:8080////",
        )) {
            assertEquals("proxy.example", parse(raw)?.host, raw)
            assertEquals(8080, parse(raw)?.port, raw)
        }
    }

    @Test
    fun `a path query or fragment is rejected`() {
        assertNull(parse("http://proxy.example:8080/path"))
        assertNull(parse("http://proxy.example:8080/path/"))
        assertNull(parse("http://proxy.example:8080?query=1"))
        assertNull(parse("http://proxy.example:8080#fragment"))
    }

    @Test
    fun `missing ports fall back to the scheme default`() {
        assertEquals(80, parse("http://proxy.example")?.port)
        assertEquals(80, parse("https://proxy.example")?.port)
        assertEquals(1080, parse("socks5://proxy.example")?.port)
        assertEquals(1080, parse("socks5h://proxy.example")?.port)
    }

    @Test
    fun `explicit ports at the edges are accepted`() {
        assertEquals(1, parse("http://proxy.example:1")?.port)
        assertEquals(65535, parse("http://proxy.example:65535")?.port)
        assertEquals(80, parse("http://proxy.example:080")?.port)
        assertEquals(80, parse("http://proxy.example:+80")?.port)
    }

    @Test
    fun `ports outside the real range are rejected`() {
        assertNull(parse("http://proxy.example:0"))
        assertNull(parse("http://proxy.example:00000"))
        assertNull(parse("http://proxy.example:-1"))
        assertNull(parse("http://proxy.example:65536"))
        assertNull(parse("http://proxy.example:1e3"))
        assertNull(parse("http://proxy.example: 8080"))
        assertNull(parse("http://proxy.example:"))
    }

    @Test
    fun `a bracketed IPv6 literal keeps its address and port`() {
        val withPort = parse("http://[2001:db8::1]:3128")
        assertEquals("2001:db8::1", withPort?.host)
        assertEquals(3128, withPort?.port)

        val defaultPort = parse("http://[2001:db8::1]")
        assertEquals("2001:db8::1", defaultPort?.host)
        assertEquals(80, defaultPort?.port)

        val trailingSlash = parse("http://[::1]:3128/")
        assertEquals("::1", trailingSlash?.host)
        assertEquals(3128, trailingSlash?.port)
    }

    @Test
    fun `malformed brackets are rejected`() {
        assertNull(parse("http://[2001:db8::1"))
        assertNull(parse("http://[]:3128"))
        assertNull(parse("http://[:3128"))
        assertNull(parse("http://[::1]junk"))
        assertNull(parse("http://[::1]:abc"))
        assertNull(parse("http://[::1]:0"))
    }

    @Test
    fun `an unbracketed IPv6 literal is a host and takes the default port`() {
        val config = parse("http://2001:db8::1")
        assertEquals("2001:db8::1", config?.host)
        assertEquals(80, config?.port)
        assertTrue(config?.usable == true)
    }

    @Test
    fun `a username without a password is valid`() {
        val config = parse("http://listener@proxy.example:8080")
        assertEquals("listener", config?.username)
        assertEquals("", config?.password)
    }

    @Test
    fun `an empty password is valid`() {
        val config = parse("http://listener:@proxy.example:8080")
        assertEquals("listener", config?.username)
        assertEquals("", config?.password)
    }

    @Test
    fun `only the first colon separates the username from the password`() {
        val config = parse("http://user:pa:ss@proxy.example:8080")
        assertEquals("user", config?.username)
        assertEquals("pa:ss", config?.password)
    }

    @Test
    fun `the last at-sign separates user info from the host`() {
        val config = parse("http://a@b@proxy.example:8080")
        assertEquals("a@b", config?.username)
        assertEquals("", config?.password)
    }

    @Test
    fun `percent escapes in credentials are decoded`() {
        val config = parse("http://u%40ser:p%3Ass@proxy.example:8080")
        assertEquals("u@ser", config?.username)
        assertEquals("p:ss", config?.password)
    }

    @Test
    fun `a plus in a password is a plus`() {
        val config = parse("http://user:a+b@proxy.example:8080")
        assertEquals("a+b", config?.password)
    }

    @Test
    fun `an invalid percent sequence is kept literally`() {
        val config = parse("http://user:p%ZZ%40@proxy.example:8080")
        assertEquals("p%ZZ@", config?.password)
    }

    @Test
    fun `a trailing percent sign is kept literally`() {
        val config = parse("http://user:p%25%40%2@proxy.example:8080")
        assertEquals("p%@%2", config?.password)
    }

    @Test
    fun `an empty host is rejected even with user info`() {
        assertNull(parse("http://:8080"))
        assertNull(parse("http://user@:8080"))
        assertNull(parse("http://user:pass@:8080"))
    }

    @Test
    fun `a space inside the authority is rejected`() {
        assertNull(parse("http://proxy example:8080"))
        assertNull(parse("http://proxy\texample:8080"))
    }

    @Test
    fun `a parsed URL is always music-only and usable`() {
        val config = parse("socks5://listener:secret@proxy.example:1080")
        assertNotNull(config)
        assertEquals(ProxyScope.MUSIC_ONLY, config.scope)
        assertTrue(config.usable)
        assertNull(ProxyValidation.validate(config))
    }
}
