package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that stand between the settings dialog and a proxy configured with
 * a pasted URL, a port of zero, or a host:port pair in the wrong field. Those
 * are the failures that read as "the proxy does nothing" rather than as an
 * error, which is why they are rejected here rather than at connect time.
 */
class ProxyConfigValidationTest {

    private fun http(host: String, port: Int = 1080) =
        ProxyConfig(mode = ProxyMode.HTTP, host = host, port = port)

    @Test
    fun `an off proxy needs no server`() {
        assertNull(ProxyValidation.validate(ProxyConfig()))
        assertFalse(ProxyConfig().enabled)
        assertFalse(ProxyConfig().usable)
    }

    @Test
    fun `a missing host is rejected`() {
        assertEquals(ProxyConfigError.HOST_MISSING, ProxyValidation.validate(http("")))
        assertEquals(ProxyConfigError.HOST_MISSING, ProxyValidation.validate(http("   ")))
    }

    @Test
    fun `a pasted scheme or path is not a host`() {
        assertEquals(
            ProxyConfigError.HOST_INVALID,
            ProxyValidation.validate(http("http://proxy.example")),
        )
        assertEquals(
            ProxyConfigError.HOST_INVALID,
            ProxyValidation.validate(http("proxy.example/socks")),
        )
        assertEquals(
            ProxyConfigError.HOST_INVALID,
            ProxyValidation.validate(http("user@proxy.example")),
        )
    }

    @Test
    fun `a host and port in the host field is rejected`() {
        assertEquals(
            ProxyConfigError.HOST_INVALID,
            ProxyValidation.validate(http("proxy.example:1080")),
        )
    }

    @Test
    fun `an IPv6 literal is allowed`() {
        assertNull(ProxyValidation.validate(http("2001:db8::1")))
        assertNull(ProxyValidation.validate(http("[2001:db8::1]")))
    }

    @Test
    fun `the port must be a real port`() {
        assertEquals(ProxyConfigError.PORT_INVALID, ProxyValidation.validate(http("proxy.example", 0)))
        assertEquals(ProxyConfigError.PORT_INVALID, ProxyValidation.validate(http("proxy.example", 65536)))
        assertEquals(ProxyConfigError.PORT_INVALID, ProxyValidation.validate(http("proxy.example", -1)))
        assertNull(ProxyValidation.validate(http("proxy.example", 1)))
        assertNull(ProxyValidation.validate(http("proxy.example", 65535)))
    }

    @Test
    fun `a complete configuration is usable`() {
        val config = ProxyConfig(
            mode = ProxyMode.SOCKS5,
            host = "proxy.example",
            port = 1080,
            username = "listener",
            password = "secret",
            scope = ProxyScope.ALL_YOUTUBE,
        )
        assertTrue(config.usable)
        assertNull(ProxyValidation.validate(config))
    }

    @Test
    fun `normalizing trims the host and username but not the password`() {
        val normalized = ProxyConfig(
            mode = ProxyMode.HTTP,
            host = "  proxy.example  ",
            port = 8080,
            username = "  listener ",
            password = " secret ",
        ).normalized()

        assertEquals("proxy.example", normalized.host)
        assertEquals("listener", normalized.username)
        assertEquals(" secret ", normalized.password)
    }

    @Test
    fun `toString never carries the password`() {
        val text = ProxyConfig(
            mode = ProxyMode.HTTP,
            host = "proxy.example",
            port = 8080,
            username = "listener",
            password = "super-secret",
        ).toString()

        assertFalse(text.contains("super-secret"))
        assertTrue(text.contains("'***'"))
    }

    @Test
    fun `a proxy URL parses credentials and is always music-only`() {
        val config = ProxyUrlParser.parse("http://listener:p%40ss@proxy.example:8080")

        assertEquals(ProxyMode.HTTP, config?.mode)
        assertEquals("proxy.example", config?.host)
        assertEquals(8080, config?.port)
        assertEquals("listener", config?.username)
        assertEquals("p@ss", config?.password)
        assertEquals(ProxyScope.MUSIC_ONLY, config?.scope)
        assertTrue(config?.usable == true)
    }

    @Test
    fun `a socks5 URL uses the conventional port when none is given`() {
        val config = ProxyUrlParser.parse("socks5://proxy.example")

        assertEquals(ProxyMode.SOCKS5, config?.mode)
        assertEquals("proxy.example", config?.host)
        assertEquals(1080, config?.port)
        assertEquals("", config?.username)
        assertEquals("", config?.password)
    }

    @Test
    fun `an IPv6 proxy URL keeps its literal and port`() {
        val config = ProxyUrlParser.parse("http://[2001:db8::1]:3128")

        assertEquals("2001:db8::1", config?.host)
        assertEquals(3128, config?.port)
        assertNull(ProxyValidation.validate(config!!))
    }

    @Test
    fun `a malformed or unsupported proxy URL is rejected`() {
        assertNull(ProxyUrlParser.parse(null))
        assertNull(ProxyUrlParser.parse(""))
        assertNull(ProxyUrlParser.parse("proxy.example:8080"))
        assertNull(ProxyUrlParser.parse("ftp://proxy.example:21"))
        assertNull(ProxyUrlParser.parse("http://"))
        assertNull(ProxyUrlParser.parse("http://proxy.example:not-a-port"))
        assertNull(ProxyUrlParser.parse("http://proxy.example:0"))
        assertNull(ProxyUrlParser.parse("http://user@:8080"))
    }
}
