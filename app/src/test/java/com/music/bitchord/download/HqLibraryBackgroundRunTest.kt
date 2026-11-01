package com.music.bitchord.download

import com.music.bitchord.data.sources.HqLibraryAutoRetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The high-quality pass used to stop whenever the app lost its window, even
 * though its WorkManager worker can raise its own foreground notification.
 * These are the two device facts the gate must distinguish: a foreground
 * worker has a way to start the download service, a plain background process
 * does not. Playback still excludes the pass in every case.
 */
class HqLibraryBackgroundRunTest {

    @Test
    fun `a plain background process without a foreground worker stays parked`() {
        assertFalse(
            hqLibraryMayRun(
                passAllowed = true,
                playbackActive = false,
                appForeground = false,
                foregroundWorker = false,
            ),
        )
    }

    @Test
    fun `a foreground worker runs the pass with the screen off`() {
        assertTrue(
            hqLibraryMayRun(
                passAllowed = true,
                playbackActive = false,
                appForeground = false,
                foregroundWorker = true,
            ),
        )
    }

    @Test
    fun `an app window still runs the pass as before`() {
        assertTrue(
            hqLibraryMayRun(
                passAllowed = true,
                playbackActive = false,
                appForeground = true,
                foregroundWorker = false,
            ),
        )
    }

    @Test
    fun `playback blocks the pass even in a foreground worker`() {
        assertFalse(
            hqLibraryMayRun(
                passAllowed = true,
                playbackActive = true,
                appForeground = false,
                foregroundWorker = true,
            ),
        )
        assertFalse(
            hqLibraryMayRun(
                passAllowed = true,
                playbackActive = true,
                appForeground = true,
                foregroundWorker = true,
            ),
        )
    }

    @Test
    fun `both switches off still block the pass`() {
        assertFalse(
            hqLibraryMayRun(
                passAllowed = false,
                playbackActive = false,
                appForeground = true,
                foregroundWorker = true,
            ),
        )
    }

    @Test
    fun `only a window or a foreground worker can start the download service`() {
        assertTrue(hqLibraryServiceStartable(appForeground = true, foregroundWorker = false))
        assertTrue(hqLibraryServiceStartable(appForeground = false, foregroundWorker = true))
        assertFalse(hqLibraryServiceStartable(appForeground = false, foregroundWorker = false))
    }

    @Test
    fun `a target stays eligible while a foreground worker runs with the screen off`() {
        val startable = hqLibraryServiceStartable(appForeground = false, foregroundWorker = true)
        assertTrue(
            HqLibraryAutoRetryPolicy.shouldAttempt(
                manual = false,
                foreground = startable,
                alreadyAtTargetTier = false,
                attempts = 0,
                lastFailureMs = 0L,
                nowMs = 1_000_000L,
            ),
        )
    }
}
