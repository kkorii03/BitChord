package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A target whose verdict vanished before its download started must leave the
 * plan even though no download ever became active for it.
 */
class HqLibraryUpgradeSettleGuardTest {

    @Test
    fun `a target that never started is settled without an active download`() {
        assertTrue(
            HqLibraryUpgrade.maySettleTarget(
                activeVideoId = null,
                videoId = "video",
                requireActive = false,
            ),
        )
        assertFalse(
            HqLibraryUpgrade.maySettleTarget(
                activeVideoId = null,
                videoId = "video",
                requireActive = true,
            ),
        )
    }

    @Test
    fun `an active target is settled only by its own id`() {
        assertTrue(
            HqLibraryUpgrade.maySettleTarget(
                activeVideoId = "video",
                videoId = "video",
                requireActive = true,
            ),
        )
        assertFalse(
            HqLibraryUpgrade.maySettleTarget(
                activeVideoId = "other",
                videoId = "video",
                requireActive = true,
            ),
        )
    }
}
