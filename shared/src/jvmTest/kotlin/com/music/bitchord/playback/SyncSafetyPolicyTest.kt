package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step
import com.music.bitchord.playback.SyncSafetyPolicy.Path
import com.music.bitchord.playback.SyncSafetyPolicy.Switches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSafetyPolicyTest {

    private val allSteps = Step.entries.toSet()
    private val off = Switches()

    @Test
    fun everySwitchDefaultsOff() {
        Path.entries.forEach { path ->
            assertFalse(SyncSafetyPolicy.isEnabled(path, off), "$path must default off")
        }
    }

    @Test
    fun nextPrepOffDropsLyricsAndSyncButKeepsVersionHqAndMix() {
        assertEquals(
            setOf(Step.VERSION, Step.HQ, Step.MIX),
            SyncSafetyPolicy.nextPrepSteps(allSteps, off),
        )
    }

    @Test
    fun nextPrepOnLeavesTheWantedStepsUntouched() {
        val on = Switches(nextPrepLyrics = true)
        assertEquals(allSteps, SyncSafetyPolicy.nextPrepSteps(allSteps, on))
        assertTrue(SyncSafetyPolicy.firstFrameCacheAllowed(on))
        assertTrue(SyncSafetyPolicy.isEnabled(Path.NEXT_PREP_LYRICS, on))
    }

    @Test
    fun nextPrepOffBlocksTheFirstFrameCache() {
        assertFalse(SyncSafetyPolicy.firstFrameCacheAllowed(off))
    }

    @Test
    fun downloadRecheckOffBlocksReadWriteAndTheCheckItself() {
        assertFalse(SyncSafetyPolicy.recheckAllowed(off))
        assertFalse(SyncSafetyPolicy.overrideReadAllowed(off))
        assertFalse(SyncSafetyPolicy.overrideWriteAllowed(off))
    }

    @Test
    fun downloadRecheckOnAllowsReadWriteAndTheCheckItself() {
        val on = Switches(downloadedRecheck = true)
        assertTrue(SyncSafetyPolicy.recheckAllowed(on))
        assertTrue(SyncSafetyPolicy.overrideReadAllowed(on))
        assertTrue(SyncSafetyPolicy.overrideWriteAllowed(on))
        assertTrue(SyncSafetyPolicy.isEnabled(Path.DOWNLOADED_RECHECK, on))
    }

    @Test
    fun hqReapplyOffBlocksTheSavedOffsetAndRate() {
        assertFalse(SyncSafetyPolicy.hqAdjustmentAllowed(off))
        assertFalse(SyncSafetyPolicy.isEnabled(Path.HQ_REAPPLY, off))
    }

    @Test
    fun hqReapplyOnAllowsTheSavedOffsetAndRate() {
        val on = Switches(hqReapply = true)
        assertTrue(SyncSafetyPolicy.hqAdjustmentAllowed(on))
        assertTrue(SyncSafetyPolicy.isEnabled(Path.HQ_REAPPLY, on))
    }

    @Test
    fun eachSwitchMovesOnlyItsOwnPath() {
        val nextPrep = Switches(nextPrepLyrics = true)
        assertEquals(allSteps, SyncSafetyPolicy.nextPrepSteps(allSteps, nextPrep))
        assertFalse(SyncSafetyPolicy.recheckAllowed(nextPrep))
        assertFalse(SyncSafetyPolicy.overrideReadAllowed(nextPrep))
        assertFalse(SyncSafetyPolicy.overrideWriteAllowed(nextPrep))
        assertFalse(SyncSafetyPolicy.hqAdjustmentAllowed(nextPrep))

        val recheck = Switches(downloadedRecheck = true)
        assertEquals(setOf(Step.VERSION, Step.HQ, Step.MIX), SyncSafetyPolicy.nextPrepSteps(allSteps, recheck))
        assertFalse(SyncSafetyPolicy.firstFrameCacheAllowed(recheck))
        assertFalse(SyncSafetyPolicy.hqAdjustmentAllowed(recheck))

        val hq = Switches(hqReapply = true)
        assertEquals(setOf(Step.VERSION, Step.HQ, Step.MIX), SyncSafetyPolicy.nextPrepSteps(allSteps, hq))
        assertFalse(SyncSafetyPolicy.firstFrameCacheAllowed(hq))
        assertFalse(SyncSafetyPolicy.recheckAllowed(hq))
        assertFalse(SyncSafetyPolicy.overrideReadAllowed(hq))
        assertFalse(SyncSafetyPolicy.overrideWriteAllowed(hq))
    }

    @Test
    fun allSwitchesOnRunEveryPath() {
        val on = Switches(nextPrepLyrics = true, downloadedRecheck = true, hqReapply = true)
        Path.entries.forEach { path ->
            assertTrue(SyncSafetyPolicy.isEnabled(path, on), "$path must be on")
        }
        assertEquals(allSteps, SyncSafetyPolicy.nextPrepSteps(allSteps, on))
        assertTrue(SyncSafetyPolicy.firstFrameCacheAllowed(on))
        assertTrue(SyncSafetyPolicy.recheckAllowed(on))
        assertTrue(SyncSafetyPolicy.overrideReadAllowed(on))
        assertTrue(SyncSafetyPolicy.overrideWriteAllowed(on))
        assertTrue(SyncSafetyPolicy.hqAdjustmentAllowed(on))
    }
}
