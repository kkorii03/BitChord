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
}
