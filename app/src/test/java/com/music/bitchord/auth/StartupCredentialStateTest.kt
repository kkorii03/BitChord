package com.music.bitchord.auth

import com.music.bitchord.data.net.NetworkReadiness
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class StartupCredentialStateTest {

    @Before
    fun resetGate() {
        NetworkReadiness.resetForTests()
    }

    @After
    fun leaveGateOpenForOtherTests() {
        NetworkReadiness.markReady()
    }

    @Test
    fun `credentials are read on the injected dispatcher, never on the caller`() = runBlocking {
        val caller = Thread.currentThread()
        val openThread = AtomicReference<Thread>()
        val stored = FakePreferences(
            mapOf(
                "channel_name" to "Listener",
            ),
        )
        val store = AuthStore {
            openThread.set(Thread.currentThread())
            stored.prefs
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "credential-loader-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            NetworkReadiness.markReady()
            val credentials = StartupCredentialState(store, dispatcher).load()

            assertNotSame("the encrypted store must not open on the caller", caller, openThread.get())
            assertEquals("credential-loader-test", openThread.get().name)
            assertNull(credentials.activeAccountId)
            assertEquals("Listener", credentials.selectedChannelName)
            assertTrue(credentials.sessions.isEmpty())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a request started before preparation waits for the stored credentials`() = runBlocking {
        val stored = FakePreferences(
            mapOf(
                "proxy_password" to "proxy-secret",
            ),
        )
        val store = AuthStore { stored.prefs }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "request-path-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            val loader = StartupCredentialState(store, dispatcher)
            val load = async(dispatcher) { loader.load() }

            delay(50)
            assertFalse("the request must wait for credentials", load.isCompleted)

            NetworkReadiness.markReady()
            val credentials = withTimeout(5_000) { load.await() }

            assertEquals("proxy-secret", store.proxyPassword)
            assertTrue(credentials.sessions.isEmpty())
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
