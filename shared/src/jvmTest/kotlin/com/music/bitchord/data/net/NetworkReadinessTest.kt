package com.music.bitchord.data.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every request path awaits one startup gate. It opens on success, and it also
 * opens on failure so a broken credential store degrades to an anonymous
 * request instead of leaving the first request waiting forever.
 */
class NetworkReadinessTest {

    @Before
    fun resetGate() {
        NetworkReadiness.resetForTests()
    }

    @After
    fun leaveGateOpenForOtherTests() {
        NetworkReadiness.markReady()
    }

    @Test
    fun `a request started before credentials are ready waits for them`() = runBlocking {
        val events = mutableListOf<String>()
        val request = launch {
            NetworkReadiness.await()
            events += "request"
        }

        delay(50)
        assertFalse("the request must still be waiting", request.isCompleted)
        assertTrue(events.isEmpty())

        events += "ready"
        NetworkReadiness.markReady()
        withTimeout(5_000) { request.join() }

        assertEquals(listOf("ready", "request"), events)
    }

    @Test
    fun `marking ready twice is harmless`() = runBlocking {
        NetworkReadiness.markReady()
        NetworkReadiness.markReady()
        withTimeout(5_000) { NetworkReadiness.await() }
        assertTrue(NetworkReadiness.isReady)
    }

    @Test
    fun `a failed preparation opens the gate so the request goes on anonymously`() = runBlocking {
        val proceeded = mutableListOf<String>()
        val request = launch {
            NetworkReadiness.await()
            proceeded += "anonymous"
        }

        NetworkReadiness.markFailed("keystore unavailable")
        withTimeout(5_000) { request.join() }

        assertEquals(listOf("anonymous"), proceeded)
        assertTrue(NetworkReadiness.isReady)
    }

    @Test
    fun `a failure after a successful preparation does not close or reopen it`() = runBlocking {
        NetworkReadiness.markReady()
        NetworkReadiness.markFailed("late failure")

        withTimeout(5_000) { NetworkReadiness.await() }
        assertTrue(NetworkReadiness.isReady)
    }
}
