package com.music.bitchord.startup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchReplayTest {

    @Test
    fun `a fresh launch is not a replay`() {
        assertFalse(
            LaunchReplay.isReplay(
                recreating = false,
                launchedFromHistory = false,
                savedFingerprint = null,
                currentFingerprint = "view:youtu.be/abc",
            ),
        )
    }

    @Test
    fun `the same launch intent restored after process death is a replay`() {
        assertTrue(
            LaunchReplay.isReplay(
                recreating = true,
                launchedFromHistory = false,
                savedFingerprint = "view:youtu.be/abc",
                currentFingerprint = "view:youtu.be/abc",
            ),
        )
    }

    @Test
    fun `a genuinely new intent delivered to a recreated activity is not a replay`() {
        assertFalse(
            LaunchReplay.isReplay(
                recreating = true,
                launchedFromHistory = false,
                savedFingerprint = "view:youtu.be/abc",
                currentFingerprint = "view:youtu.be/xyz",
            ),
        )
    }

    @Test
    fun `a task opened from recents is always a replay`() {
        assertTrue(
            LaunchReplay.isReplay(
                recreating = false,
                launchedFromHistory = true,
                savedFingerprint = null,
                currentFingerprint = "view:youtu.be/abc",
            ),
        )
    }

    @Test
    fun `a recreated activity without a saved fingerprint is treated as a replay`() {
        assertTrue(
            LaunchReplay.isReplay(
                recreating = true,
                launchedFromHistory = false,
                savedFingerprint = null,
                currentFingerprint = "view:youtu.be/abc",
            ),
        )
    }
}
