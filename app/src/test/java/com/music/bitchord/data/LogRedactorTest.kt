package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @Test
    fun cookieHeaderIsRedacted() {
        val out = LogRedactor.redact("Cookie: SID=abc123; SAPISID=def456")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("def456"))
        assertTrue(out.contains("Cookie: [redacted]"))
    }

    @Test
    fun sessionIdentifiersAreRedacted() {
        val out = LogRedactor.redact("SAPISID=secretvalue SID=another __Secure-3PSID=x")
        assertFalse(out.contains("secretvalue"))
        assertFalse(out.contains("another"))
        assertTrue(out.contains("SAPISID=[redacted]"))
        assertTrue(out.contains("SID=[redacted]"))
    }

    @Test
    fun authorizationHeaderIsRedacted() {
        val out = LogRedactor.redact("Authorization: Bearer ya29.secret-token")
        assertEquals("Authorization: [redacted]", out)
    }

    @Test
    fun tokenAndBasicAuthorizationSchemesAreFullyRedacted() {
        assertEquals(
            "Authorization: [redacted]",
            LogRedactor.redact("Authorization: Token 0123456789abcdef"),
        )
        assertEquals(
            "Proxy-Authorization: [redacted]",
            LogRedactor.redact("Proxy-Authorization: Basic dXNlcjpwYXNz"),
        )
    }

    @Test
    fun schemelessAuthorizationValueIsFullyRedacted() {
        val out = LogRedactor.redact("Authorization: abcdef0123456789 trailing words")
        assertEquals("Authorization: [redacted]", out)
        assertFalse(out.contains("abcdef0123456789"))
        assertFalse(out.contains("trailing"))
    }

    @Test
    fun multiPartSapisidAuthorizationIsRedactedToEndOfLine() {
        val out = LogRedactor.redact(
            "Authorization: SAPISIDHASH 1700000000_deadbeef " +
                "SAPISID1PHASH 1700000000_onepart SAPISID3PHASH 1700000000_threepart",
        )
        assertEquals("Authorization: [redacted]", out)
        assertFalse(out.contains("deadbeef"))
        assertFalse(out.contains("onepart"))
        assertFalse(out.contains("threepart"))
    }

    @Test
    fun apiKeysAreRedacted() {
        val out = LogRedactor.redact("key AIzaSyA1234567890abcdefghij and sk-or-v1-abcdefghijklmnop")
        assertFalse(out.contains("AIzaSyA1234567890abcdefghij"))
        assertFalse(out.contains("sk-or-v1-abcdefghijklmnop"))
        assertTrue(out.contains("[redacted-key]"))
    }

    @Test
    fun emailsAreRedacted() {
        assertEquals("user [redacted-email] signed in", LogRedactor.redact("user someone@example.com signed in"))
    }

    @Test
    fun googlevideoQueryIsDroppedButHostAndPathKept() {
        val out = LogRedactor.redact("GET https://rr3---sn-x.googlevideo.com/videoplayback?expire=1&sig=secret")
        assertEquals("GET https://rr3---sn-x.googlevideo.com/videoplayback", out)
        assertFalse(out.contains("sig=secret"))
    }

    @Test
    fun everyMediaUrlLosesItsQueryBecauseItIsSigned() {
        val out = LogRedactor.redact(
            "JioSaavn 320kbps https://aac.saavncdn.com/1/song.mp4?sign=secret&expires=1 " +
                "module streamUrl=https://cdn.example.test/a.m4a?token=also-secret",
        )
        assertFalse(out.contains("sign=secret"))
        assertFalse(out.contains("token=also-secret"))
        assertTrue(out.contains("https://aac.saavncdn.com/1/song.mp4"))
        assertTrue(out.contains("https://cdn.example.test/a.m4a"))
    }

    @Test
    fun proxyCredentialsInUrlAreRedactedAndFragmentIsDropped() {
        val out = LogRedactor.redact(
            "proxy https://user:pass@proxy.example:8080/path?token=secret#fragment",
        )
        assertEquals("proxy https://[redacted]@proxy.example:8080/path", out)
        assertFalse(out.contains("pass"))
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("fragment"))
    }

    @Test
    fun websocketQueryIsDroppedToo() {
        val out = LogRedactor.redact("socket wss://party.example/ws?token=secret")
        assertEquals("socket wss://party.example/ws", out)
        assertFalse(out.contains("token=secret"))
    }

    @Test
    fun ordinaryLinesAreUnchanged() {
        val line = "12:00:00.000 D/BitChord resolved abc123 via direct IOS @ 256kbps"
        assertEquals(line, LogRedactor.redact(line))
    }
}
