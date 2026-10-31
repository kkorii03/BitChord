package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gapless auto advance must count as already sounding.
 *
 * Media3 stays READY across the item boundary, so `onIsPlayingChanged` never
 * fires and `audibleMediaId` still names the outgoing row. The timeline guard
 * and the end of `onTrackBecameCurrent` both hold read-ahead and the next-row
 * bundle back unless this is recognised. A skip into a buffering row must keep
 * waiting for its first sound.
 */
class RowAudibilityTest {

    @Test
    fun `a gapless auto advance is already sounding`() {
        assertTrue(
            rowAlreadySounding(
                alreadyAudible = false,
                previousEnded = true,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `a crossfade is already sounding without a transition`() {
        assertTrue(
            rowAlreadySounding(
                alreadyAudible = true,
                previousEnded = false,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `a skip into a buffering row is not audible yet`() {
        assertFalse(
            rowAlreadySounding(
                alreadyAudible = false,
                previousEnded = false,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `an ended player that is no longer playing is not audible`() {
        assertFalse(
            rowAlreadySounding(
                alreadyAudible = false,
                previousEnded = true,
                isPlaying = false,
            ),
        )
    }
}
