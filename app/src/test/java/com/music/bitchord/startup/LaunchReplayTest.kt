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
                savedFingerprints = null,
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
                savedFingerprints = setOf("view:youtu.be/abc"),
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
                savedFingerprints = setOf("view:youtu.be/abc"),
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
                savedFingerprints = null,
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
                savedFingerprints = null,
                currentFingerprint = "view:youtu.be/abc",
            ),
        )
    }

    @Test
    fun `the task's original intent is still a replay after a newer one was served`() {
        // Task started with A, then onNewIntent delivered B and setIntent(B).
        // Recreation restores the task's base intent A; keeping only the newest
        // fingerprint (B) made A look like a fresh request and served it again.
        assertTrue(
            LaunchReplay.isReplay(
                recreating = true,
                launchedFromHistory = false,
                savedFingerprints = setOf("view:youtu.be/a", "view:youtu.be/b"),
                currentFingerprint = "view:youtu.be/a",
            ),
        )
    }

    @Test
    fun `a widget player request is part of the launch fingerprint`() {
        // Both intents have no action, data, type or text; only the widget's
        // open-player extra tells them apart. Leaving it out made every widget
        // tap hash to the same empty string as any other bare launch.
        val plain = LaunchReplay.fingerprintOf(
            action = null,
            data = null,
            type = null,
            text = null,
            query = null,
            stream = null,
            openPlayer = false,
        )
        val widget = LaunchReplay.fingerprintOf(
            action = null,
            data = null,
            type = null,
            text = null,
            query = null,
            stream = null,
            openPlayer = true,
        )
        assertFalse(plain == widget)
    }
}
