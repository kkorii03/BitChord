package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A verdict can arrive after a worker has taken the first target but before it
 * has marked that target active. Clearing the plan then used to leave the
 * worker settling into an empty plan with no rebuild, so the new verdict was
 * not used until the next app start or playback.
 */
class HqLibraryRebuildPolicyTest {

    @Test
    fun `an active replacement always rebuilds after its verdict`() {
        assertTrue(rebuildAfterVerdict(activeVideoId = "yt1", planSize = 0))
    }

    @Test
    fun `a plan already picked but not yet active still rebuilds`() {
        assertTrue(rebuildAfterVerdict(activeVideoId = null, planSize = 1))
    }

    @Test
    fun `a verdict with no run in flight does not schedule an extra pass`() {
        assertFalse(rebuildAfterVerdict(activeVideoId = null, planSize = 0))
    }
}
