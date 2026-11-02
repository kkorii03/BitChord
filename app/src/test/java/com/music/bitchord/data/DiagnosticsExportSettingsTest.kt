package com.music.bitchord.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsExportSettingsTest {

    @Test
    fun `credential keys are masked and ordinary settings are kept`() {
        val lines = DiagnosticsExport.settingsSnapshotLines(
            mapOf(
                "lastfm_session_key" to "session-secret",
                "proxy_password" to "hunter2",
                "youtube_cookie" to "SID=abc123",
                "api_key" to "AIzaSyA1234567890abcdefghij",
                "smart_fade_enabled" to true,
                "crossfade_seconds" to 3,
            ),
        )
        val text = lines.joinToString("\n")
        assertFalse(text.contains("session-secret"))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("AIzaSyA1234567890abcdefghij"))
        assertTrue(text.contains("setting: lastfm_session_key=[redacted]"))
        assertTrue(text.contains("setting: proxy_password=[redacted]"))
        assertTrue(text.contains("setting: smart_fade_enabled=true"))
        assertTrue(text.contains("setting: crossfade_seconds=3"))
    }

    @Test
    fun `a secret-looking value is redacted even under an ordinary key`() {
        val lines = DiagnosticsExport.settingsSnapshotLines(
            mapOf("some_url" to "https://example.test/x?token=secret"),
        )
        assertFalse(lines.single().contains("token=secret"))
    }
}
