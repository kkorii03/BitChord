package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Configuration edges the settings dialog can produce: an off proxy with
 * half-filled fields, a backup that must never take down a working primary,
 * and the redaction guarantee on the object's string form.
 */
class ProxyConfigEdgeTest {

    private fun http(host: String = "proxy.example", port: Int = 8080) =
        ProxyConfig(mode = ProxyMode.HTTP, host = host, port = port)

    @Test
    fun `an off proxy ignores every other field`() {
        val config = ProxyConfig(
            mode = ProxyMode.OFF,
            host = "not a host",
            port = -1,
            backupHost = "also not a host",
            backupPort = 99999,
        )
        assertFalse(config.enabled)
        assertFalse(config.usable)
        assertFalse(config.backupUsable)
        assertNull(ProxyValidation.validate(config))
        assertNull(ProxyValidation.validateBackup(config))
    }

    @Test
    fun `an enabled proxy is not usable without a real host and port`() {
        assertFalse(http(host = "").usable)
        assertFalse(http(host = "   ").usable)
        assertFalse(http(port = 0).usable)
        assertFalse(http(port = 65536).usable)
        assertTrue(http().usable)
        assertTrue(http(port = 1).usable)
        assertTrue(http(port = 65535).usable)
    }

    @Test
    fun `whitespace anywhere in the host is invalid`() {
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validate(http("proxy example")))
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validate(http("proxy\texample")))
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validate(http("proxy\nexample")))
    }

    @Test
    fun `a backslash or an at-sign is not a host character`() {
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validate(http("proxy\\example")))
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validate(http("listener@proxy")))
    }

    @Test
    fun `a single colon is a pasted port but several mean IPv6`() {
        assertEquals(
            ProxyConfigError.HOST_INVALID,
            ProxyValidation.validate(http("proxy.example:8080")),
        )
        assertNull(ProxyValidation.validate(http("2001:db8::1")))
        assertNull(ProxyValidation.validate(http("::1")))
        assertNull(ProxyValidation.validate(http("fe80::1%eth0")))
    }

    @Test
    fun `a malformed backup is optional and leaves the primary usable`() {
        val badHost = http().copy(backupHost = "backup.example:9090", backupPort = 9090)
        assertTrue(badHost.usable)
        assertFalse(badHost.backupUsable)
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validateBackup(badHost))

        val badPort = http().copy(backupHost = "backup.example", backupPort = 0)
        assertTrue(badPort.usable)
        assertFalse(badPort.backupUsable)
        assertEquals(ProxyConfigError.PORT_INVALID, ProxyValidation.validateBackup(badPort))
    }

    @Test
    fun `a blank backup is no backup and is not an error`() {
        val config = http().copy(backupHost = "   ", backupPort = 9090)
        assertFalse(config.backupUsable)
        assertNull(ProxyValidation.validateBackup(config))
    }

    @Test
    fun `a valid backup requires a usable primary too`() {
        val config = http(host = "").copy(backupHost = "backup.example", backupPort = 9090)
        assertFalse(config.usable)
        assertFalse(config.backupUsable)
        assertNull(ProxyValidation.validateBackup(config))
    }

    @Test
    fun `normalizing strips IPv6 brackets from both servers`() {
        val normalized = http(host = "[2001:db8::1]")
            .copy(backupHost = "[2001:db8::2]", backupPort = 9090)
            .normalized()
        assertEquals("2001:db8::1", normalized.host)
        assertEquals("2001:db8::2", normalized.backupHost)
        assertEquals(9090, normalized.backupPort)
    }

    @Test
    fun `normalizing drops a backup port that has no backup host`() {
        val normalized = http().copy(backupHost = "   ", backupPort = 9090).normalized()
        assertEquals("", normalized.backupHost)
        assertEquals(0, normalized.backupPort)
    }

    @Test
    fun `normalizing trims the username and host but preserves the password`() {
        val normalized = http(host = "  proxy.example  ")
            .copy(username = "  listener  ", password = "  secret  ")
            .normalized()
        assertEquals("proxy.example", normalized.host)
        assertEquals("listener", normalized.username)
        assertEquals("  secret  ", normalized.password)
    }

    @Test
    fun `toString redacts both credentials and keeps the shape readable`() {
        val withSecrets = http().copy(username = "listener", password = "super-secret")
        val text = withSecrets.toString()
        assertFalse(text.contains("listener"))
        assertFalse(text.contains("super-secret"))
        assertTrue(text.contains("username='***'"))
        assertTrue(text.contains("password='***'"))
        assertTrue(text.contains("mode=HTTP"))
        assertTrue(text.contains("host='proxy.example'"))

        val empty = http().toString()
        assertTrue(empty.contains("username=''"))
        assertTrue(empty.contains("password=''"))
    }
}
