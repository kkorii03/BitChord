package com.music.bitchord.playback

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two network judgements that used to fail in opposite directions: a timeout
 * was mistaken for a cancelled read (so a dead host was credited with a
 * success), and no active network was mistaken for online (so offline playback
 * still went looking for a stream).
 */
class NetworkOfflinePolicyTest {

    @Test
    fun `a socket timeout is a failed transfer`() {
        assertFalse(isTransferCancellation(SocketTimeoutException("timed out")))
    }

    @Test
    fun `an interrupted read is a cancellation`() {
        assertTrue(isTransferCancellation(InterruptedIOException("read-ahead cancelled")))
    }

    @Test
    fun `an ordinary io failure is a failed transfer`() {
        assertFalse(isTransferCancellation(IOException("truncated stream")))
    }

    @Test
    fun `no active network is offline`() {
        assertTrue(
            offlineFromCapabilities(
                hasCapabilities = false,
                hasInternet = false,
                captivePortal = false,
            ),
        )
    }

    @Test
    fun `internet without a captive portal is online`() {
        assertFalse(
            offlineFromCapabilities(
                hasCapabilities = true,
                hasInternet = true,
                captivePortal = false,
            ),
        )
    }

    @Test
    fun `a captive portal is offline`() {
        assertTrue(
            offlineFromCapabilities(
                hasCapabilities = true,
                hasInternet = true,
                captivePortal = true,
            ),
        )
    }

    @Test
    fun `a network without internet is offline`() {
        assertTrue(
            offlineFromCapabilities(
                hasCapabilities = true,
                hasInternet = false,
                captivePortal = false,
            ),
        )
    }
}
