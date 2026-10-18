package com.music.bitchord.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conditions under which a Canvas follows its clip for a backdrop.
 *
 * Only a surface that has painted a frame, that the app is showing, and that
 * no transition is holding belongs in the periodic readback; everything else
 * would decode and capture for nobody.
 */
class CanvasFrameRefreshTest {

    @Test
    fun `refreshes while rendered, visible and not paused`() {
        assertTrue(
            shouldRefreshFrames(
                refreshFrameEveryMs = 3_000L,
                rendered = true,
                foreground = true,
                pausedForTransition = false,
            ),
        )
    }

    @Test
    fun `does not refresh without a requested interval`() {
        assertFalse(
            shouldRefreshFrames(
                refreshFrameEveryMs = null,
                rendered = true,
                foreground = true,
                pausedForTransition = false,
            ),
        )
    }

    @Test
    fun `does not refresh before a frame has rendered`() {
        assertFalse(
            shouldRefreshFrames(
                refreshFrameEveryMs = 3_000L,
                rendered = false,
                foreground = true,
                pausedForTransition = false,
            ),
        )
    }

    @Test
    fun `does not refresh while the app is off screen`() {
        assertFalse(
            shouldRefreshFrames(
                refreshFrameEveryMs = 3_000L,
                rendered = true,
                foreground = false,
                pausedForTransition = false,
            ),
        )
    }

    @Test
    fun `does not refresh during a caller-driven transition`() {
        assertFalse(
            shouldRefreshFrames(
                refreshFrameEveryMs = 3_000L,
                rendered = true,
                foreground = true,
                pausedForTransition = true,
            ),
        )
    }

    @Test
    fun `does not start a tight loop for a non-positive interval`() {
        assertFalse(
            shouldRefreshFrames(
                refreshFrameEveryMs = 0L,
                rendered = true,
                foreground = true,
                pausedForTransition = false,
            ),
        )
    }
}
