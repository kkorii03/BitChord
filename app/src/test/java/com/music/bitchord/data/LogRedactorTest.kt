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
    fun ordinaryLinesAreUnchanged() {
        val line = "12:00:00.000 D/BitChord resolved abc123 via direct IOS @ 256kbps"
        assertEquals(line, LogRedactor.redact(line))
    }
}
