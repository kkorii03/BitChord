package com.music.bitchord

import androidx.media3.common.Player
import com.music.bitchord.playback.autoplayQueueNeedsRefresh
import com.music.bitchord.playback.repeatOneSeedShouldClear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayFallbackTest {
    @Test
    fun `enabled autoplay refreshes when current track has no queue after it`() {
        assertTrue(
            autoplayQueueNeedsRefresh(
                enabled = true,
                repeatAll = false,
                currentIndex = 2,
                itemCount = 3,
                loadInProgress = false,
            ),
        )
    }

    @Test
    fun `an existing queue or active load does not start a duplicate refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 3, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, 2, 3, true))
    }

    @Test
    fun `disabled autoplay repeat all and a missing current track do not refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(false, false, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, true, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, -1, 0, false))
    }

    @Test
    fun `repeat one does not refresh a queue it will not advance through`() {
        assertFalse(
            autoplayQueueNeedsRefresh(
                enabled = true,
                repeatAll = false,
                currentIndex = 2,
                itemCount = 3,
                loadInProgress = false,
                repeatOne = true,
            ),
        )
    }

    @Test
    fun `leaving repeat one releases the seed so the queue can refill`() {
        // The seed stays latched while repeat-one loops the same track. Without
        // clearing it, the reload after repeat-one ends is skipped as same-seed.
        assertTrue(repeatOneSeedShouldClear(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF))
        assertTrue(repeatOneSeedShouldClear(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL))
        assertFalse(repeatOneSeedShouldClear(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE))
        assertFalse(repeatOneSeedShouldClear(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_OFF))
        assertFalse(repeatOneSeedShouldClear(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ONE))
    }
}
