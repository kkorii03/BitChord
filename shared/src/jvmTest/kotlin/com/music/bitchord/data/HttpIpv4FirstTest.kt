package com.music.bitchord.data

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app's DNS order: IPv4 first so one unreachable IPv6 route cannot decide a
 * probe, with IPv6 still present as the fallback.
 */
class HttpIpv4FirstTest {

    private fun address(vararg bytes: Int): InetAddress =
        InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

    private val v4a = address(192, 0, 2, 1)
    private val v4b = address(198, 51, 100, 2)
    private val v6a = address(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    private val v6b = address(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)

    @Test
    fun `ipv4 addresses move ahead of ipv6 and keep their own order`() {
        assertEquals(
            listOf(v4a, v4b, v6a, v6b),
            Http.ipv4First(listOf(v6a, v4a, v6b, v4b)),
        )
    }

    @Test
    fun `ipv6 is kept as the fallback rather than dropped`() {
        assertEquals(
            listOf(v4a, v6a),
            Http.ipv4First(listOf(v6a, v4a)),
        )
    }

    @Test
    fun `an ipv6-only answer is left alone`() {
        assertEquals(listOf(v6a, v6b), Http.ipv4First(listOf(v6a, v6b)))
    }
}
