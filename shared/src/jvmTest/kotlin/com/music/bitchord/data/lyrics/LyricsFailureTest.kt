package com.music.bitchord.data.lyrics

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The provider sheet says why a provider had no lyrics. These are the cases
 * that used to collapse into one null, plus the statuses worth distinguishing.
 */
class LyricsFailureTest {

    @Test
    fun aProviderWithNothingToSayIsNotFound() {
        assertEquals(LyricsFailure.NOT_FOUND, LyricsFailure.from(null))
        assertEquals(LyricsFailure.NOT_FOUND, LyricsFailure.fromStatus(404))
    }

    @Test
    fun aRequestPastItsBudgetIsTimeout() {
        assertEquals(LyricsFailure.TIMEOUT, LyricsFailure.from(SocketTimeoutException()))
        assertEquals(LyricsFailure.TIMEOUT, LyricsFailure.fromStatus(408))
    }

    @Test
    fun transportFailuresAreNetworkErrors() {
        assertEquals(LyricsFailure.NETWORK, LyricsFailure.from(UnknownHostException("no dns")))
        assertEquals(LyricsFailure.NETWORK, LyricsFailure.from(IOException("connection reset")))
        assertEquals(LyricsFailure.NETWORK, LyricsFailure.fromStatus(503))
    }

    @Test
    fun aRefusedRequestIsBlockedRatherThanMissing() {
        assertEquals(LyricsFailure.BLOCKED, LyricsFailure.fromStatus(403))
        assertEquals(LyricsFailure.BLOCKED, LyricsFailure.fromStatus(429))
        assertEquals(LyricsFailure.BLOCKED, LyricsFailure.from(LyricsHttpStatusException(403)))
    }
}
