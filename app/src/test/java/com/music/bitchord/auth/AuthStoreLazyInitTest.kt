package com.music.bitchord.auth

import com.music.bitchord.testutil.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The encrypted credential store is opened once, off the launch path.
 *
 * A read that arrives before that open finishes must wait for the same open,
 * not start another one and not return an empty cookie.
 */
class AuthStoreLazyInitTest {

    private val stored = FakePreferences(
        mapOf(
            "cookie" to "SID=abc; SAPISID=secret",
            "active_google_account_id_v2" to "account-1",
        ),
    )

    @Test
    fun `a read before ready waits for the open and returns the stored value`() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val store = AuthStore {
            opens.incrementAndGet()
            opening.countDown()
            release.await(5, TimeUnit.SECONDS)
            stored.prefs
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<String?> { store.cookie }
            assertTrue("the open should have started", opening.await(5, TimeUnit.SECONDS))
            assertFalse("the reader must wait for the open", reader.isDone)

            release.countDown()

            assertEquals("SID=abc; SAPISID=secret", reader.get(5, TimeUnit.SECONDS))
            assertEquals("account-1", store.activeAccountId)
            assertEquals(1, opens.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent first reads share one open`() {
        val opens = AtomicInteger()
        val store = AuthStore {
            opens.incrementAndGet()
            stored.prefs
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val readers = (1..8).map {
                executor.submit<String?> {
                    start.await()
                    store.cookie
                }
            }
            start.countDown()
            readers.forEach { assertEquals("SID=abc; SAPISID=secret", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, opens.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an explicit prewarm is not repeated by the first read`() {
        val opens = AtomicInteger()
        val store = AuthStore {
            opens.incrementAndGet()
            stored.prefs
        }

        store.ensureReady()
        store.ensureReady()
        store.cookie

        assertEquals(1, opens.get())
        assertTrue(store.isReady)
    }
}
