package com.music.bitchord.ko

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UPG-speed item 1: a client that kept answering "client response unavailable"
 * on this network is left out of the next catalog walk, and only then.
 * KoDeadClients does not exist on the old code, so this class fails there.
 */
class KoDeadClientsTest {
    private val proxy = KoDeadClients.route(viaProxy = true, proxyGeneration = 7)
    private val direct = KoDeadClients.route(viaProxy = false, proxyGeneration = 7)

    @Before
    fun reset() = KoDeadClients.clear("test")

    @After
    fun tearDown() = KoDeadClients.clear("test")

    @Test
    fun skipsOnlyAfterThreeDifferentVideosFail() {
        KoDeadClients.failed(proxy, "VISIONOS", "a")
        KoDeadClients.failed(proxy, "VISIONOS", "a")
        KoDeadClients.failed(proxy, "VISIONOS", "b")
        assertEquals(emptySet<String>(), KoDeadClients.dead(proxy))
        KoDeadClients.failed(proxy, "VISIONOS", "c")
        assertEquals(setOf("VISIONOS"), KoDeadClients.dead(proxy))
    }

    @Test
    fun aClientThatServedIsNeverSkipped() {
        listOf("a", "b", "c").forEach { KoDeadClients.failed(proxy, "VISIONOS", it) }
        KoDeadClients.served(proxy, "VISIONOS")
        assertEquals(emptySet<String>(), KoDeadClients.dead(proxy))
        listOf("d", "e", "f").forEach { KoDeadClients.failed(proxy, "VISIONOS", it) }
        assertEquals(emptySet<String>(), KoDeadClients.dead(proxy))
    }

    @Test
    fun routesAreSeparate() {
        listOf("a", "b", "c").forEach { KoDeadClients.failed(proxy, "WEB_EMBEDDED_PLAYER", it) }
        assertEquals(emptySet<String>(), KoDeadClients.dead(direct))
        assertEquals(setOf("WEB_EMBEDDED_PLAYER"), KoDeadClients.dead(proxy))
    }

    @Test
    fun aNewProxyGenerationStartsFresh() {
        listOf("a", "b", "c").forEach { KoDeadClients.failed(proxy, "VISIONOS", it) }
        val next = KoDeadClients.route(viaProxy = true, proxyGeneration = 8)
        KoDeadClients.failed(next, "VISIONOS", "a")
        assertEquals(emptySet<String>(), KoDeadClients.dead(proxy))
        assertEquals(emptySet<String>(), KoDeadClients.dead(next))
    }

    @Test
    fun anotherNetworkClearsTheMemoButTheSameOneDoesNot() {
        KoDeadClients.onNetwork("100")
        listOf("a", "b", "c").forEach { KoDeadClients.failed(direct, "VISIONOS", it) }
        KoDeadClients.onNetwork("100")
        assertEquals(setOf("VISIONOS"), KoDeadClients.dead(direct))
        KoDeadClients.onNetwork("101")
        assertEquals(emptySet<String>(), KoDeadClients.dead(direct))
    }

    @Test
    fun logEventsCountOnlyInsideAWalkAndOnlyUnavailableAnswers() = runBlocking {
        val unavailable = "client response unavailable"
        fun answer(client: String, transit: Boolean = false, noToken: Boolean = false) = mapOf(
            "client" to client,
            "profilePresent" to "true",
            "tokenUnavailable" to noToken.toString(),
            "requestFailure" to transit.toString(),
            "failurePresent" to "true",
        )
        repeat(3) { KoDeadClients.observe("PlayerClientDirector", unavailable, answer("WEB")) } // outside any walk
        listOf("a", "b", "c").forEach { video ->
            withContext(KoDeadClients.walkContext(proxy, video)) {
                KoDeadClients.observe("PlayerClientDirector", "client selected", answer("IOS"))
                KoDeadClients.observe("SomethingElse", unavailable, answer("ANDROID"))
                KoDeadClients.observe("PlayerClientDirector", unavailable, answer("WEB_CREATOR", transit = true))
                KoDeadClients.observe("PlayerClientDirector", unavailable, answer("MWEB", noToken = true))
                // The walk's coroutine hops threads; the walk it belongs to goes with it.
                withContext(Dispatchers.IO) {
                    KoDeadClients.observe("PlayerClientDirector", unavailable, answer("VISIONOS"))
                }
            }
        }
        assertEquals(setOf("VISIONOS"), KoDeadClients.dead(proxy))
        assertTrue(KoDeadClients.dead(direct).isEmpty())
    }
}
