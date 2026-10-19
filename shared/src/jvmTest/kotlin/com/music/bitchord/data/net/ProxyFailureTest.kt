package com.music.bitchord.data.net

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyFailureTest {

    @Test
    fun `a refused connection to the proxy is proxy down`() {
        assertTrue(ProxyFailure.isProxyDown(ConnectException("Connection refused")))
    }

    @Test
    fun `a connect or read timeout on the proxy is proxy down`() {
        assertTrue(ProxyFailure.isProxyDown(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun `an unresolvable proxy host is proxy down`() {
        assertTrue(ProxyFailure.isProxyDown(UnknownHostException("proxy.example")))
    }

    @Test
    fun `a refused CONNECT handshake is proxy down`() {
        val failure = IOException(
            "Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407 Proxy Authentication Required\"",
        )
        assertTrue(ProxyFailure.isProxyDown(failure))
    }

    @Test
    fun `a plain proxy verdict in the message is proxy down`() {
        assertTrue(ProxyFailure.isProxyDown(IOException("proxy authentication failed")))
        assertTrue(ProxyFailure.isProxyDown(IOException("HTTP 407")))
    }

    @Test
    fun `a wrapped proxy failure is still proxy down`() {
        val wrapped = IOException("request failed", ConnectException("Connection refused"))
        assertTrue(ProxyFailure.isProxyDown(wrapped))
    }

    @Test
    fun `an unrelated failure is not proxy down`() {
        assertFalse(ProxyFailure.isProxyDown(IOException("Connection reset")))
        assertFalse(ProxyFailure.isProxyDown(IOException("HTTP 500")))
        assertFalse(ProxyFailure.isProxyDown(IOException()))
        assertFalse(ProxyFailure.isProxyDown(IllegalStateException("not signed in")))
    }
}
