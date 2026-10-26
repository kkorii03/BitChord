package com.music.bitchord.data.net

import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Failure classification is what decides whether a proxy is condemned or the
 * phone's own network is. Wrapped, cyclic and message-only failures are the
 * cases that used to escape the first version of this rule.
 */
class ProxyFailureEdgeTest {

    @Test
    fun `no route to the proxy host is proxy down`() {
        assertTrue(ProxyFailure.isProxyDown(NoRouteToHostException("no route")))
    }

    @Test
    fun `a socket timeout wrapped twice is still proxy down`() {
        val failure = IOException(
            "request failed",
            IOException("connect failed", SocketTimeoutException("connect timed out")),
        )
        assertTrue(ProxyFailure.isProxyDown(failure))
    }

    @Test
    fun `a proxy message at the bottom of a deep chain is found`() {
        var failure: Throwable = IOException("Unable to tunnel through proxy")
        repeat(20) { failure = IllegalStateException("layer $it", failure) }
        assertTrue(ProxyFailure.isProxyDown(failure))
    }

    @Test
    fun `a cause cycle is not mistaken for a proxy failure`() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertFalse(ProxyFailure.isProxyDown(first))
    }

    @Test
    fun `a null or empty message is not a proxy verdict`() {
        assertFalse(ProxyFailure.isProxyDown(IOException()))
        assertFalse(ProxyFailure.isProxyDown(RuntimeException()))
        assertFalse(ProxyFailure.isProxyDown(IllegalStateException("")))
    }

    @Test
    fun `the proxy words are case-insensitive`() {
        assertTrue(ProxyFailure.isProxyDown(IOException("PROXY AUTHENTICATION REQUIRED")))
        assertTrue(ProxyFailure.isProxyDown(IOException("UNABLE TO TUNNEL THROUGH PROXY")))
        assertTrue(ProxyFailure.isProxyDown(IOException("Proxy returns 502")))
    }

    @Test
    fun `a bare 407 in a message is treated as proxy auth`() {
        assertTrue(ProxyFailure.isProxyDown(IOException("HTTP 407")))
        assertTrue(ProxyFailure.isProxyDown(IOException("status=407")))
    }

    @Test
    fun `an unrelated http status is not proxy down`() {
        assertFalse(ProxyFailure.isProxyDown(IOException("HTTP 403")))
        assertFalse(ProxyFailure.isProxyDown(IOException("HTTP 500")))
        assertFalse(ProxyFailure.isProxyDown(IOException("status=404")))
    }

    @Test
    fun `only a successful direct retry proves the proxy is the broken part`() {
        assertTrue(ProxyFailure.directRetryProvesProxyDown(directRetrySucceeded = true))
        assertFalse(ProxyFailure.directRetryProvesProxyDown(directRetrySucceeded = false))
    }
}
