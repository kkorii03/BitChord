package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class ProxyHealthMemoryTest {

    private var now = 1_000_000L
    private val memory = ProxyHealthMemory { now }

    @Test
    fun `a fresh memory does not bypass the proxy`() {
        assertFalse(memory.isUnreachable())
    }

    @Test
    fun `a failure is remembered for the whole five-minute window`() {
        memory.markUnreachable()
        assertTrue(memory.isUnreachable())

        now += ProxyHealthMemory.WINDOW_MS - 1
        assertTrue(memory.isUnreachable())
    }

    @Test
    fun `the memory clears itself when the window expires`() {
        memory.markUnreachable()
        now += ProxyHealthMemory.WINDOW_MS
        assertFalse(memory.isUnreachable())
        assertFalse(memory.isUnreachable())
    }

    @Test
    fun `a reachable mark clears the memory at once`() {
        memory.markUnreachable()
        memory.markReachable()
        assertFalse(memory.isUnreachable())
    }

    @Test
    fun `a new failure restarts the window`() {
        memory.markUnreachable()
        now += ProxyHealthMemory.WINDOW_MS - 1_000L
        memory.markUnreachable()
        now += 1_000L
        assertTrue(memory.isUnreachable())
    }

    @Test
    fun `a direct retry that succeeds proves the proxy alone is down`() {
        assertTrue(ProxyFailure.directRetryProvesProxyDown(directRetrySucceeded = true))
    }

    @Test
    fun `a direct retry that fails proves only that the phone is offline`() {
        assertFalse(ProxyFailure.directRetryProvesProxyDown(directRetrySucceeded = false))
    }

    @Test
    fun `a route pinned to the proxy is not bypassed by the health memory`() {
        YouTubeProxy.update(
            ProxyConfig(mode = ProxyMode.HTTP, host = "proxy.example", port = 8080),
        )
        try {
            ProxyHealthMonitor.recordUnreachable()
            val uri = URI("https://music.youtube.com/youtubei/v1/browse")

            assertEquals(Proxy.NO_PROXY, YouTubeProxy.proxySelector.select(uri).single())

            val pinned = YouTubeProxy.forceProxySelector.select(uri).single()
            assertEquals(Proxy.Type.HTTP, pinned.type())
            assertEquals(8080, (pinned.address() as InetSocketAddress).port)
        } finally {
            YouTubeProxy.update(ProxyConfig())
        }
    }
}
