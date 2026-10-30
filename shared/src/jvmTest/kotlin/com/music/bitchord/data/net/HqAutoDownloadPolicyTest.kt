package com.music.bitchord.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HqAutoDownloadPolicyTest {

    @Test
    fun `a refusal without retry-after waits one minute then doubles`() {
        assertEquals(60_000L, HqAutoDownloadPolicy.retryDelayMs(null, attempt = 0, nowMs = 0L))
        assertEquals(120_000L, HqAutoDownloadPolicy.retryDelayMs(null, attempt = 1, nowMs = 0L))
        assertEquals(240_000L, HqAutoDownloadPolicy.retryDelayMs(null, attempt = 2, nowMs = 0L))
        assertEquals(480_000L, HqAutoDownloadPolicy.retryDelayMs(null, attempt = 3, nowMs = 0L))
    }

    @Test
    fun `the exponential wait is capped at thirty minutes`() {
        assertEquals(HqAutoDownloadPolicy.MAX_RETRY_MS, HqAutoDownloadPolicy.retryDelayMs(null, attempt = 20, nowMs = 0L))
        assertEquals(30 * 60_000L, HqAutoDownloadPolicy.MAX_RETRY_MS)
    }

    @Test
    fun `retry-after is obeyed instead of the exponential`() {
        assertEquals(125_000L, HqAutoDownloadPolicy.retryDelayMs("125", attempt = 3, nowMs = 0L))
        assertEquals(0L, HqAutoDownloadPolicy.retryDelayMs("0", attempt = 0, nowMs = 0L))
    }

    @Test
    fun `a rate limit or a reset stream is a retry, not a failed download`() {
        assertTrue(HqAutoDownloadPolicy.isTransientFailure(429, "Download failed (HTTP 429)"))
        assertTrue(HqAutoDownloadPolicy.isTransientFailure(503, "HTTP 503"))
        assertTrue(
            HqAutoDownloadPolicy.isTransientFailure(
                code = null,
                message = "stream was reset: INTERNAL_ERROR",
            ),
        )
        assertTrue(HqAutoDownloadPolicy.isTransientFailure(null, "Download failed (HTTP 429)"))
        assertFalse(HqAutoDownloadPolicy.isTransientFailure(404, "Download failed (HTTP 404)"))
        assertFalse(HqAutoDownloadPolicy.isTransientFailure(null, "검증된 고음질 음원을 받을 수 없습니다"))
    }

    @Test
    fun `a lost connection is transient rather than a verdict about the file`() {
        val networkFailures = listOf(
            "Unable to resolve host \"cdn.example.com\": No address associated with hostname",
            "Failed to connect to /127.0.0.1:8080",
            "Connection refused",
            "Software caused connection abort",
            "Network is unreachable",
            "No route to host",
            "java.net.ConnectException: ECONNREFUSED",
        )

        for (message in networkFailures) {
            assertTrue(
                "$message must be retried, not parked",
                HqAutoDownloadPolicy.isTransientFailure(code = null, message = message),
            )
        }
    }

    @Test
    fun `a missing recording is still a permanent failure`() {
        assertFalse(HqAutoDownloadPolicy.isTransientFailure(null, "트랙을 찾을 수 없습니다"))
        assertFalse(HqAutoDownloadPolicy.isTransientFailure(410, "Download failed (HTTP 410)"))
    }

    @Test
    fun `automatic failures are never counted as user failures`() {
        assertFalse(HqAutoDownloadPolicy.countsAsUserFailure(automatic = true))
        assertTrue(HqAutoDownloadPolicy.countsAsUserFailure(automatic = false))
    }

    @Test
    fun `an automatic target waits for playback and for a busy host`() {
        assertTrue(HqAutoDownloadPolicy.shouldStartNow(true, playbackActive = false, hostBusy = false, autoRunning = 0))
        assertFalse(HqAutoDownloadPolicy.shouldStartNow(true, playbackActive = true, hostBusy = false, autoRunning = 0))
        assertFalse(HqAutoDownloadPolicy.shouldStartNow(true, playbackActive = false, hostBusy = true, autoRunning = 0))
        assertFalse(HqAutoDownloadPolicy.shouldStartNow(true, playbackActive = false, hostBusy = false, autoRunning = 1))
        assertTrue("a user tap is never gated by the automatic pass", HqAutoDownloadPolicy.shouldStartNow(false, playbackActive = true, hostBusy = true, autoRunning = 9))
    }
}
