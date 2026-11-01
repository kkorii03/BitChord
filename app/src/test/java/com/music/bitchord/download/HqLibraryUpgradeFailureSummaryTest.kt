package com.music.bitchord.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished high-quality pass has to leave its failures on screen.
 *
 * [HqLibraryUpgrade.Progress] used to be cleared with the pass, so a run that
 * failed forty of forty looked exactly like one that had just finished. The
 * summary is now kept until the next run starts and the same target failing
 * twice in one run is still one failed song, not two.
 */
class HqLibraryUpgradeFailureSummaryTest {

    private val failedField = HqLibraryUpgrade::class.java
        .getDeclaredField("failedThisRun")
        .apply { isAccessible = true }
    private val finishedField = HqLibraryUpgrade::class.java
        .getDeclaredField("runFinished")
        .apply { isAccessible = true }

    @After
    fun tearDown() {
        reset()
    }

    @Test
    fun `a finished run with failures shows its summary`() {
        assertTrue(
            HqLibraryUpgrade.Progress(failed = 2, finished = true).failedVisible,
        )
    }

    @Test
    fun `a running pass waits for the run to finish before showing failures`() {
        assertFalse(
            HqLibraryUpgrade.Progress(
                running = true,
                done = 1,
                total = 3,
                failed = 2,
                finished = false,
            ).failedVisible,
        )
    }

    @Test
    fun `a finished run with no failures stays quiet`() {
        assertFalse(
            HqLibraryUpgrade.Progress(finished = true, failed = 0).failedVisible,
        )
    }

    @Test
    fun `the same target failing twice is counted once`() {
        reset()

        register("video-1\u0002source\u0002track")
        register("video-1\u0002source\u0002track")
        register("video-2\u0002source\u0002track")

        assertEquals(2, failedField.getInt(HqLibraryUpgrade))
        assertTrue(finishedField.getBoolean(HqLibraryUpgrade).not())
    }

    private fun register(key: String) {
        HqLibraryUpgrade::class.java
            .getDeclaredMethod("registerRunFailureLocked", String::class.java)
            .apply { isAccessible = true }
            .invoke(HqLibraryUpgrade, key)
    }

    private fun reset() {
        HqLibraryUpgrade::class.java
            .getDeclaredMethod("resetRunSummaryLocked")
            .apply { isAccessible = true }
            .invoke(HqLibraryUpgrade)
    }
}
