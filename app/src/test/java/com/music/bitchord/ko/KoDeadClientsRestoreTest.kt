package com.music.bitchord.ko

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * cold1119: a client proven dead on a route is still skipped by the next
 * process, so a cold start does not pay VISIONOS x3 + WEB_EMBEDDED_PLAYER again.
 * On the old code the memo was memory only (no init/place), so this fails there.
 */
class KoDeadClientsRestoreTest {
    private var clock = 1_000_000_000L
    private val writes = mutableListOf<String>()
    private val proxy1 = KoDeadClients.route(viaProxy = true, proxyGeneration = 1, place = "abc")

    @Before
    fun reset() {
        KoDeadClients.init(null, { writes += it }, { clock })
        KoDeadClients.clear("test")
        writes.clear()
    }

    @After
    fun tearDown() {
        KoDeadClients.clear("test")
        KoDeadClients.init(null, {})
    }

    private fun killThree(route: String, client: String) =
        listOf("a", "b", "c").forEach { KoDeadClients.failed(route, client, it) }

    @Test
    fun aNewProcessSkipsWhatTheLastOneProvedDead() {
        killThree(proxy1, "VISIONOS")
        killThree(proxy1, "WEB_EMBEDDED_PLAYER")
        assertEquals("written once per client, not per failure", 2, writes.size)
        val stored = writes.last()

        // New process: empty memory, generation starts again, same server.
        KoDeadClients.clear("process restart")
        KoDeadClients.init(stored, { writes += it }, { clock + 60_000 })
        val again = KoDeadClients.route(viaProxy = true, proxyGeneration = 1, place = "abc")
        assertEquals(setOf("VISIONOS", "WEB_EMBEDDED_PLAYER"), KoDeadClients.dead(again))
        // Generation is per process: a later generation on the same server still skips them.
        assertEquals(setOf("VISIONOS", "WEB_EMBEDDED_PLAYER"),
            KoDeadClients.dead(KoDeadClients.route(viaProxy = true, proxyGeneration = 5, place = "abc")))
        // Another server, or the direct route, inherits nothing.
        assertTrue(KoDeadClients.dead(KoDeadClients.route(viaProxy = true, proxyGeneration = 1, place = "xyz")).isEmpty())
        assertTrue(KoDeadClients.dead(KoDeadClients.route(viaProxy = false, proxyGeneration = 1, place = "abc")).isEmpty())
    }

    @Test
    fun aRecordOlderThanItsWindowIsNotRestored() {
        killThree(proxy1, "VISIONOS")
        val stored = writes.last()
        KoDeadClients.clear("process restart")
        KoDeadClients.init(stored, {}, { clock + KoDeadClients.RESTORED_TTL_MS + 1 })
        assertTrue(KoDeadClients.dead(proxy1).isEmpty())
    }

    @Test
    fun aRestoredClientExpiresInsideTheProcessWithoutATimer() {
        killThree(proxy1, "VISIONOS")
        val stored = writes.last()
        KoDeadClients.clear("process restart")
        var at = clock + 1_000
        KoDeadClients.init(stored, {}, { at })
        assertEquals(setOf("VISIONOS"), KoDeadClients.dead(proxy1))
        at = clock + KoDeadClients.RESTORED_TTL_MS
        assertTrue(KoDeadClients.dead(proxy1).isEmpty())
    }

    @Test
    fun aRestoredClientThatServesIsForgottenAndTheStoreRewritten() {
        killThree(proxy1, "VISIONOS")
        val stored = writes.last()
        KoDeadClients.clear("process restart")
        writes.clear()
        KoDeadClients.init(stored, { writes += it }, { clock })
        KoDeadClients.served(proxy1, "VISIONOS")
        assertTrue(KoDeadClients.dead(proxy1).isEmpty())
        assertEquals(listOf(""), writes)
        KoDeadClients.served(proxy1, "WEB_CREATOR") // nothing stored for it: no write
        assertEquals(1, writes.size)
    }

    @Test
    fun aNetworkOrSessionChangeAlsoWipesTheStore() {
        KoDeadClients.onNetwork("100")
        killThree(proxy1, "VISIONOS")
        KoDeadClients.onNetwork("101")
        assertEquals("", writes.last())
        assertTrue(KoDeadClients.dead(proxy1).isEmpty())
        val before = writes.size
        KoDeadClients.clear("session changed") // nothing stored now: no write
        assertEquals(before, writes.size)
    }

    @Test
    fun aRouteWithoutAPlaceIsNeverStored() {
        killThree(KoDeadClients.route(viaProxy = true, proxyGeneration = 1), "VISIONOS")
        assertTrue(writes.isEmpty())
    }

    @Test
    fun brokenStoredLinesAreIgnored() {
        KoDeadClients.init("garbage\n\tVISIONOS\t1\nproxy@abc\tVISIONOS\tnotanumber\nproxy@abc\tIOS\t${clock - 5}", {}, { clock })
        assertEquals(setOf("IOS"), KoDeadClients.dead(proxy1))
    }

    @Test
    fun noProxyMeansThePlaceOff() {
        assertEquals("off", KoDeadClients.place())
    }
}
