package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The learning rule's edges: path formatting, the winner's expiry and miss
 * budget, the direct path's sliding window, and what survives a restart.
 */
class ResolutionOrderEdgeTest {

    private class MemoryStore : ResolutionOrder.Store {
        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()

        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String): Long? = longs[key]
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    private var clock = 1_000_000L

    private fun order(store: ResolutionOrder.Store? = null) = ResolutionOrder(store) { clock }

    @Test
    fun `itx paths round-trip through their manifest id`() {
        assertEquals("itx:WEB_CREATOR", ResolutionOrder.itx("WEB_CREATOR"))
        assertEquals("WEB_CREATOR", ResolutionOrder.itxManifest(ResolutionOrder.itx("WEB_CREATOR")))
        assertEquals("A:B:C", ResolutionOrder.itxManifest(ResolutionOrder.itx("A:B:C")))
    }

    @Test
    fun `only an itx path has a manifest id`() {
        assertNull(ResolutionOrder.itxManifest(null))
        assertNull(ResolutionOrder.itxManifest(""))
        assertNull(ResolutionOrder.itxManifest(ResolutionOrder.DIRECT))
        assertNull(ResolutionOrder.itxManifest(ResolutionOrder.NEW_PIPE))
        assertNull(ResolutionOrder.itxManifest("itx:"))
        assertNull(ResolutionOrder.itxManifest("itx:   "))
        assertNull(ResolutionOrder.itxManifest("ITX:WEB"))
    }

    @Test
    fun `extraction is never learned and does not replace the winner`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteServed(ResolutionOrder.NEW_PIPE)
        assertEquals("itx:WEB_CREATOR", order.preferred())
    }

    @Test
    fun `a refusal of a path that is not the winner does not clear the winner`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        order.noteRefused(ResolutionOrder.itx("TVHTML5"))
        assertEquals("itx:WEB_CREATOR", order.preferred())
        assertEquals(1, order.refusalsRecently(ResolutionOrder.itx("TVHTML5")))
    }

    @Test
    fun `the winner needs two misses of its own`() {
        val order = order()
        val winner = ResolutionOrder.itx("WEB_CREATOR")
        order.noteServed(winner)
        order.noteRefused(winner)
        order.noteRefused(ResolutionOrder.itx("TVHTML5"))
        assertEquals(winner, order.preferred())
        order.noteRefused(winner)
        assertNull(order.preferred())
    }

    @Test
    fun `recent refusals keep only the last eight asks`() {
        val order = order()
        repeat(20) { order.noteRefused(ResolutionOrder.itx("WEB_CREATOR")) }
        assertEquals(8, order.refusalsRecently(ResolutionOrder.itx("WEB_CREATOR")))
    }

    @Test
    fun `five refusals alone do not bench direct until the window is full`() {
        val order = order()
        repeat(5) { order.noteDirectRefused() }
        assertTrue(order.directAllowed())
        order.noteDirectRefused()
        assertFalse(order.directAllowed())
    }

    @Test
    fun `four refusals in a full window do not bench direct`() {
        val order = order()
        repeat(2) { order.noteDirectServed() }
        repeat(4) { order.noteDirectRefused() }
        assertTrue(order.directAllowed())
    }

    @Test
    fun `a success after five refusals does not itself bench direct`() {
        val order = order()
        repeat(5) { order.noteDirectRefused() }
        order.noteDirectServed()
        assertTrue(order.directAllowed())
        order.noteDirectRefused()
        assertFalse(order.directAllowed())
    }

    @Test
    fun `an old refusal falls out of the six-track window`() {
        val order = order()
        repeat(5) { order.noteDirectRefused() }
        repeat(2) { order.noteDirectServed() }
        assertTrue(order.directAllowed())
    }

    @Test
    fun `the direct bench lasts exactly thirty minutes`() {
        val order = order()
        repeat(6) { order.noteDirectRefused() }
        assertFalse(order.directAllowed())
        clock += ResolutionOrder.DIRECT_BENCH_MS - 1
        assertFalse(order.directAllowed())
        clock += 1
        assertTrue(order.directAllowed())
    }

    @Test
    fun `a learned winner expires at exactly twenty-four hours`() {
        val order = order()
        order.noteServed(ResolutionOrder.itx("WEB_CREATOR"))
        clock += ResolutionOrder.WINNER_TTL_MS - 1
        assertEquals("itx:WEB_CREATOR", order.preferred())
        clock += 1
        assertNull(order.preferred())
    }

    @Test
    fun `a stored winner one millisecond before expiry is loaded`() {
        val store = MemoryStore()
        clock = ResolutionOrder.WINNER_TTL_MS + 10_000L
        val at = clock - ResolutionOrder.WINNER_TTL_MS + 1
        store.putString("stream_order.winner", ResolutionOrder.itx("WEB_CREATOR"))
        store.putLong("stream_order.winner_at", at)
        assertEquals("itx:WEB_CREATOR", order(store).preferred())
    }

    @Test
    fun `a stored winner at expiry is ignored`() {
        val store = MemoryStore()
        clock = ResolutionOrder.WINNER_TTL_MS + 10_000L
        store.putString("stream_order.winner", ResolutionOrder.itx("WEB_CREATOR"))
        store.putLong("stream_order.winner_at", clock - ResolutionOrder.WINNER_TTL_MS)
        assertNull(order(store).preferred())
    }

    @Test
    fun `a stored winner with no timestamp or a blank path is ignored`() {
        val store = MemoryStore()
        store.putString("stream_order.winner", ResolutionOrder.itx("WEB_CREATOR"))
        store.putLong("stream_order.winner_at", 0L)
        assertNull(order(store).preferred())

        val blank = MemoryStore()
        blank.putString("stream_order.winner", "   ")
        blank.putLong("stream_order.winner_at", clock)
        assertNull(order(blank).preferred())
    }

    @Test
    fun `attaching a store after construction loads its winner`() {
        val store = MemoryStore()
        order(store).noteServed(ResolutionOrder.itx("WEB_CREATOR"))

        val reloaded = order()
        assertNull(reloaded.preferred())
        reloaded.attach(store)
        assertEquals("itx:WEB_CREATOR", reloaded.preferred())
    }

    @Test
    fun `clearing the winner persists an empty path and zero timestamp`() {
        val store = MemoryStore()
        val order = order(store)
        val winner = ResolutionOrder.itx("WEB_CREATOR")
        order.noteServed(winner)
        assertEquals(winner, store.strings["stream_order.winner"])
        order.noteRefused(winner)
        order.noteRefused(winner)
        assertEquals("", store.strings["stream_order.winner"])
        assertEquals(0L, store.longs["stream_order.winner_at"])
    }

    @Test
    fun `a benched direct winner is hidden and returns after the bench`() {
        val order = order()
        order.noteServed(ResolutionOrder.DIRECT)
        repeat(6) { order.noteDirectRefused() }
        assertNull(order.preferred())
        clock += ResolutionOrder.DIRECT_BENCH_MS
        assertEquals(ResolutionOrder.DIRECT, order.preferred())
    }
}
