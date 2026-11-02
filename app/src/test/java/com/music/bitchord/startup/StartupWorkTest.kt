package com.music.bitchord.startup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
}
