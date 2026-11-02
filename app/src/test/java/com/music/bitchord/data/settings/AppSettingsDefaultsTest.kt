package com.music.bitchord.data.settings

import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults and pure rung rules that decide what a fresh install hears.
 *
 * These are deliberately about values and truth tables, not about the preference
 * file: a default that silently changes is a change in every new listener's
 * first play, and the source-permission table is the one place where a ceiling
 * becomes "which catalogue may answer".
 */
class AppSettingsDefaultsTest {

    @Test
    fun `lossless asks every source kind`() {
        SourceKind.entries.forEach { kind ->
            assertTrue("LOSSLESS should permit $kind", AudioQuality.LOSSLESS.permits(kind))
        }
    }

    @Test
    fun `high skips only the sources that can serve lossless`() {
        assertTrue(AudioQuality.HIGH.permits(SourceKind.YOUTUBE))
        assertTrue(AudioQuality.HIGH.permits(SourceKind.JIOSAAVN))
        SourceKind.entries.filter { it.canServeLossless }.forEach { kind ->
            assertFalse("HIGH should skip $kind", AudioQuality.HIGH.permits(kind))
        }
    }

    @Test
    fun `medium and low are youtube only`() {
        listOf(AudioQuality.MEDIUM, AudioQuality.LOW).forEach { quality ->
            SourceKind.entries.forEach { kind ->
                assertEquals(
                    "wrong permission for $quality/$kind",
                    kind == SourceKind.YOUTUBE,
                    quality.permits(kind),
                )
            }
        }
    }

    @Test
    fun `the youtube fallback bitrate ladder keeps low at 64 and the rest uncapped`() {
        assertEquals(64, AudioQuality.LOW.maxKbps)
        assertEquals(Int.MAX_VALUE, AudioQuality.MEDIUM.maxKbps)
        assertEquals(Int.MAX_VALUE, AudioQuality.HIGH.maxKbps)
        assertEquals(Int.MAX_VALUE, AudioQuality.LOSSLESS.maxKbps)
    }

    @Test
    fun `download rungs keep only the lossless one bit exact`() {
        assertEquals(128, DownloadQuality.STANDARD.maxKbps)
        assertFalse(DownloadQuality.STANDARD.keepsLossless)
        assertEquals(Int.MAX_VALUE, DownloadQuality.HIGH.maxKbps)
        assertFalse(DownloadQuality.HIGH.keepsLossless)
        assertEquals(Int.MAX_VALUE, DownloadQuality.LOSSLESS.maxKbps)
        assertTrue(DownloadQuality.LOSSLESS.keepsLossless)
    }

    @Test
    fun `cache and sync limits keep their published defaults`() {
        assertEquals(512L * 1024 * 1024, AppSettings.DEFAULT_CACHE_LIMIT_BYTES)
        assertEquals(10L * 1024 * 1024 * 1024, AppSettings.MAX_CACHE_LIMIT_BYTES)
        assertEquals(Long.MAX_VALUE, AppSettings.UNLIMITED_CACHE_LIMIT_BYTES)
        assertEquals(-5_000, AppSettings.MIN_LYRICS_OFFSET_MS)
        assertEquals(5_000, AppSettings.MAX_LYRICS_OFFSET_MS)
        assertEquals(5, AppSettings.DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS)
        assertEquals(0, AppSettings.MIN_UPGRADE_LENGTH_SLACK_SECONDS)
        assertEquals(10, AppSettings.MAX_UPGRADE_LENGTH_SLACK_SECONDS)
        assertEquals(5, AppSettings.MAX_PINNED_PLAYLISTS)
        assertEquals("KR", AppSettings.DEFAULT_CONTENT_COUNTRY)
    }

    @Test
    fun `music-only is on for a fresh install and keeps a stored choice`() {
        assertTrue(preferMusicOnlyDefault(stored = null, existingInstall = false))
        assertTrue(preferMusicOnlyDefault(stored = true, existingInstall = false))
        assertFalse(preferMusicOnlyDefault(stored = false, existingInstall = false))
    }

    @Test
    fun `music-only stays off for an existing install that never stored it`() {
        assertFalse(preferMusicOnlyDefault(stored = null, existingInstall = true))
        assertTrue(preferMusicOnlyDefault(stored = true, existingInstall = true))
        assertFalse(preferMusicOnlyDefault(stored = false, existingInstall = true))
    }

    @Test
    fun `the signature url is trimmed and capped`() {
        assertEquals("", AppSettings.normalizeSignatureApiUrl(""))
        assertEquals("https://api.example", AppSettings.normalizeSignatureApiUrl("  https://api.example/  "))
        assertEquals("https://api.example/v1", AppSettings.normalizeSignatureApiUrl("https://api.example/v1///"))
        assertEquals(500, AppSettings.normalizeSignatureApiUrl("x".repeat(900)).length)
        assertEquals("x".repeat(500), AppSettings.normalizeSignatureApiUrl("x".repeat(900)))
    }
}
