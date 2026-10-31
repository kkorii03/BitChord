package com.music.bitchord.data.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The debug-only switch that decides what a direct connection does.
 *
 * Off keeps `Proxy.NO_PROXY`; on asks the device proxy (the emulator's global
 * http_proxy). A release build ignores the switch and keeps the direct route
 * it has always had.
 */
class DirectConnectionProxySwitchTest {

    private val uri = URI("https://www.youtube.com/youtubei/v1/player")
    private val deviceProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("10.0.2.2", 3128))
    private val deviceSelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(deviceProxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    @AfterTest
    fun tearDown() {
        YouTubeProxy.setDebugDirectSwitchAllowed(true)
        YouTubeProxy.setDirectViaSystemProxy(false)
    }

    @Test
    fun `switch off keeps a direct connection on NO_PROXY`() {
        YouTubeProxy.setDebugDirectSwitchAllowed(true)
        YouTubeProxy.setDirectViaSystemProxy(false)

        assertEquals(listOf(Proxy.NO_PROXY), YouTubeProxy.directConnection(uri, deviceSelector))
    }

    @Test
    fun `switch on makes a direct connection follow the device proxy`() {
        YouTubeProxy.setDebugDirectSwitchAllowed(true)
        YouTubeProxy.setDirectViaSystemProxy(true)

        assertEquals(listOf(deviceProxy), YouTubeProxy.directConnection(uri, deviceSelector))
    }

    @Test
    fun `release ignores the switch and keeps its direct route`() {
        YouTubeProxy.setDebugDirectSwitchAllowed(false)
        YouTubeProxy.setDirectViaSystemProxy(true)

        assertFalse(YouTubeProxy.directViaSystemProxyEnabled)
        assertEquals(listOf(deviceProxy), YouTubeProxy.directConnection(uri, deviceSelector))
    }
}
