package com.music.bitchord.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Plain stores no longer queue behind the Keystore-bound encrypted opens. */
class StartupWorkTest {

    @Test
    fun `plain stores do not wait behind an encrypted open`() {
        val encryptedStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val plainDone = CountDownLatch(1)
        try {
            StartupWork.background("auth-store") {
                encryptedStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertTrue("encrypted open did not start", encryptedStarted.await(5, TimeUnit.SECONDS))

            StartupWork.background("local-playlists") { plainDone.countDown() }

            assertTrue(
                "plain startup work waited behind the encrypted open",
                plainDone.await(2, TimeUnit.SECONDS),
            )
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `attach waits for the settings read and the session restore`() {
        val settingsReady = CountDownLatch(1)
        val restoreDone = CountDownLatch(1)
        val attachDone = CountDownLatch(1)
        val sawRestore = AtomicBoolean(false)

        StartupWork.startSession(
            settingsReady = settingsReady,
            restore = { restoreDone.countDown() },
            attach = {
                sawRestore.set(restoreDone.count == 0L)
                attachDone.countDown()
            },
        )

        assertFalse(
            "attach ran before the settings read finished",
            attachDone.await(200, TimeUnit.MILLISECONDS),
        )
        settingsReady.countDown()
        assertTrue("attach did not run", attachDone.await(5, TimeUnit.SECONDS))
        assertTrue("attach ran before the session restore", sawRestore.get())
    }
}
