package com.music.bitchord.auth

import com.music.bitchord.data.net.NetworkReadiness
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A request that starts before startup preparation finishes must still carry
 * both credentials the first YouTube request needs: the restored session cookie
 * and the proxy password.
 */
class StartupCredentialGateTest {

    @Before
    fun resetGate() {
        NetworkReadiness.resetForTests()
    }

    @After
    fun leaveGateOpenForOtherTests() {
        NetworkReadiness.markReady()
    }

    @Test
    fun `the first request sees the cookie and proxy password restored just before ready`() = runBlocking {
        val stored = FakePreferences(
            mapOf(
                "cookie" to "SID=abc; SAPISID=secret",
                "proxy_password" to "proxy-secret",
            ),
        )
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = AuthStore {
            opening.countDown()
            release.await(5, TimeUnit.SECONDS)
            stored.prefs
        }

        val preparation = launch(Dispatchers.Default) {
            store.ensureReady()
            NetworkReadiness.markReady()
        }
        assertTrue("the encrypted store should be opening", opening.await(5, TimeUnit.SECONDS))

        var requestCookie: String? = null
        var requestProxyPassword: String? = null
        val request = launch(Dispatchers.Default) {
            NetworkReadiness.await()
            requestCookie = store.cookie
            requestProxyPassword = store.proxyPassword
        }

        delay(50)
        assertNull("the request must wait for the gate", requestCookie)

        release.countDown()

        withTimeout(5_000) { preparation.join() }
        withTimeout(5_000) { request.join() }

        assertEquals("SID=abc; SAPISID=secret", requestCookie)
        assertEquals("proxy-secret", requestProxyPassword)
    }
}
