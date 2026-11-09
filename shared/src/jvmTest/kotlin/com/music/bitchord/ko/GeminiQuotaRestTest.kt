package com.music.bitchord.ko

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiQuotaRestTest {
    @Test
    fun dailyQuotaIdIsRecognisedMinuteLimitIsNot() {
        assertTrue(KoLyricsTranslation.isDailyQuota("GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
        assertTrue(KoLyricsTranslation.isDailyQuota("Quota exceeded: 20 requests per day"))
        assertFalse(KoLyricsTranslation.isDailyQuota("GenerateRequestsPerMinutePerProjectPerModel-FreeTier"))
    }

    @Test
    fun minuteLimitRestsFiveMinutes() {
        assertEquals(5L * 60_000L, KoLyricsTranslation.geminiRestMs(false, 1_000_000L))
    }

    @Test
    fun dailyQuotaRestsUntilPacificMidnight() {
        // 2026-11-09 12:00 PST (UTC-8) = 20:00 UTC; midnight PST is 12 h away.
        val noonPacific = java.time.Instant.parse("2026-11-09T20:00:00Z").toEpochMilli()
        assertEquals(12L * 3_600_000L, KoLyricsTranslation.geminiRestMs(true, noonPacific))
        // One minute before reset still rests at least the 5-minute floor.
        val almost = java.time.Instant.parse("2026-11-10T07:59:00Z").toEpochMilli()
        assertEquals(5L * 60_000L, KoLyricsTranslation.geminiRestMs(true, almost))
    }

    @Test
    fun networkFailureTriesNextModelOnceButTimeoutDoesNot() {
        assertTrue(KoLyricsTranslation.geminiTriesNextAfter(java.io.IOException("Required SETTINGS preface not received"), 1))
        assertTrue(KoLyricsTranslation.geminiTriesNextAfter(javax.net.ssl.SSLHandshakeException("connection closed"), 1))
        assertTrue(KoLyricsTranslation.geminiTriesNextAfter(java.net.SocketException("Software caused connection abort"), 1))
        assertFalse(KoLyricsTranslation.geminiTriesNextAfter(java.net.SocketTimeoutException("timeout"), 1))
        assertFalse(KoLyricsTranslation.geminiTriesNextAfter(java.io.InterruptedIOException("timeout"), 1))
        assertFalse(KoLyricsTranslation.geminiTriesNextAfter(java.io.IOException("second failure"), 2))
    }
}
