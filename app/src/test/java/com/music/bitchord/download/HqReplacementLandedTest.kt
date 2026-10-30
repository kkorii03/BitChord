package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A successful download is not proof that the high-quality pass's replacement
 * landed: the queue can refuse the pass's request because the listener already
 * queued the same id at a different rung. Only the recorded verified pair
 * answers that question.
 */
class HqReplacementLandedTest {

    @Test
    fun `a successful download with the verified pair landed`() {
        assertTrue(hqReplacementLanded(downloadSucceeded = true, applied = true))
    }

    @Test
    fun `a successful download without the verified pair did not land`() {
        assertFalse(hqReplacementLanded(downloadSucceeded = true, applied = false))
    }

    @Test
    fun `a failed download never landed`() {
        assertFalse(hqReplacementLanded(downloadSucceeded = false, applied = true))
        assertFalse(hqReplacementLanded(downloadSucceeded = false, applied = false))
    }

    @Test
    fun `a successful download already at least as good as the target landed`() {
        assertTrue(
            hqReplacementLanded(
                downloadSucceeded = true,
                applied = false,
                atLeastAsGood = true,
            ),
        )
    }

    @Test
    fun `a successful download below the target and without its pair did not land`() {
        assertFalse(
            hqReplacementLanded(
                downloadSucceeded = true,
                applied = false,
                atLeastAsGood = false,
            ),
        )
    }
}
