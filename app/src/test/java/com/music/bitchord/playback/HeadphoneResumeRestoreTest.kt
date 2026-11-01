package com.music.bitchord.playback

import com.music.bitchord.testutil.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A paused queue restored after the process died still knows when it paused.
 *
 * The service used to keep that time only in memory, so headphones connecting
 * after a restart found a queue that looked like it had never played and never
 * resumed it.
 */
class HeadphoneResumeRestoreTest {

    private lateinit var preferences: FakePreferences

    @Before
    fun setUp() {
        preferences = FakePreferences()
        LastPlayed.initForTesting(preferences.prefs)
    }

    @Test
    fun `a pause survives a process restart`() {
        val pausedAt = 1_000_000L
        LastPlayed.savePausedAt(pausedAt)

        // A new process reads the same preferences through a fresh object state.
        LastPlayed.initForTesting(preferences.prefs)

        assertEquals(pausedAt, LastPlayed.pausedAt())
        assertEquals(
            pausedAt,
            HeadphoneResumePolicy.restoredPauseAt(LastPlayed.pausedAt(), pausedAt + 60_000L),
        )
    }

    @Test
    fun `a pause older than the resume window is not restored`() {
        val pausedAt = 1_000_000L
        LastPlayed.savePausedAt(pausedAt)

        assertNull(
            HeadphoneResumePolicy.restoredPauseAt(
                LastPlayed.pausedAt(),
                pausedAt + HeadphoneResumePolicy.WINDOW_MS + 1L,
            ),
        )
    }

    @Test
    fun `a pause timestamp from before a reboot is not restored`() {
        val pausedAt = 1_000_000L
        LastPlayed.savePausedAt(pausedAt)

        // elapsedRealtime starts again at zero after a reboot, so the saved
        // value is "in the future" and cannot describe this session.
        assertNull(HeadphoneResumePolicy.restoredPauseAt(LastPlayed.pausedAt(), 500_000L))
    }

    @Test
    fun `playing again clears the saved pause`() {
        LastPlayed.savePausedAt(1_000_000L)
        LastPlayed.clearPausedAt()

        assertNull(LastPlayed.pausedAt())
        assertNull(HeadphoneResumePolicy.restoredPauseAt(LastPlayed.pausedAt(), 1_000_001L))
    }
}
