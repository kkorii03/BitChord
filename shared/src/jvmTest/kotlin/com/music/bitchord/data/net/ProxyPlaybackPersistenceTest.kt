package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The persistence half of [ProxyPlayback]: a remembered id is a latency hint,
 * not a permanent record, so anything older than a month is dropped on load.
 */
class ProxyPlaybackPersistenceTest {

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `entries older than thirty days are dropped`() {
        val now = 1_000L * day
        val entries = mapOf(
            "fresh" to now - day,
            "stale" to now - 30L * day - 1L,
        )
        assertEquals(mapOf("fresh" to now - day), ProxyPlayback.filterFresh(entries, now))
    }

    @Test
    fun `an entry exactly thirty days old is kept`() {
        val now = 1_000L * day
        val entries = mapOf("edge" to now - 30L * day)
        assertEquals(entries, ProxyPlayback.filterFresh(entries, now))
    }

    @Test
    fun `an empty memory stays empty`() {
        assertEquals(emptyMap(), ProxyPlayback.filterFresh(emptyMap(), 123L))
    }
}
