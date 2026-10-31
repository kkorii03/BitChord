package com.music.bitchord.playback.audio

import com.music.bitchord.playback.CrossfadeGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioSilenceRecoveryTest {

    @Test
    fun theLadderRebuildsThenReprepareThenReresolves() {
        assertEquals(AudioSilenceRecovery.Step.REBUILD, AudioSilenceRecovery.nextStep(0))
        assertEquals(AudioSilenceRecovery.Step.REPREPARE, AudioSilenceRecovery.nextStep(1))
        assertEquals(AudioSilenceRecovery.Step.RERESOLVE, AudioSilenceRecovery.nextStep(2))
        assertEquals(AudioSilenceRecovery.Step.RERESOLVE, AudioSilenceRecovery.nextStep(9))
    }

    @Test
    fun aSilentOutputWithALiveDecoderIsAFaultAndStartsAtRebuild() {
        val decision = AudioSilenceRecovery.decide(
            stalled = false,
            outputSilentForMs = AudioSilenceRecovery.OUTPUT_SILENT_MS,
            decoderSilentThroughoutOutput = false,
            recoveries = 0,
            msSinceLastRecovery = Long.MAX_VALUE,
        )
        assertEquals(AudioSilenceRecovery.Action.RECOVER, decision.action)
        assertEquals(AudioSilenceRecovery.Step.REBUILD, decision.step)
    }

    @Test
    fun aFailedRebuildEscalatesOnlyAfterItsRecheckWindow() {
        val tooSoon = AudioSilenceRecovery.decide(
            stalled = false,
            outputSilentForMs = AudioSilenceRecovery.OUTPUT_SILENT_MS + 20_000L,
            decoderSilentThroughoutOutput = false,
            recoveries = 1,
            msSinceLastRecovery = AudioSilenceRecovery.STEP_RECHECK_MS - 1L,
        )
        assertEquals(AudioSilenceRecovery.Action.NONE, tooSoon.action)
        assertNull(tooSoon.step)

        val escalated = AudioSilenceRecovery.decide(
            stalled = false,
            outputSilentForMs = AudioSilenceRecovery.OUTPUT_SILENT_MS + 20_000L,
            decoderSilentThroughoutOutput = false,
            recoveries = 1,
            msSinceLastRecovery = AudioSilenceRecovery.STEP_RECHECK_MS,
        )
        assertEquals(AudioSilenceRecovery.Action.RECOVER, escalated.action)
        assertEquals(AudioSilenceRecovery.Step.REPREPARE, escalated.step)
    }

    @Test
    fun aGenuinelyQuietPassageDoesNotTriggerRecovery() {
        // The decoder went quiet before the output did: the file is silent, not
        // a broken fader or AudioTrack.
        val decision = AudioSilenceRecovery.decide(
            stalled = false,
            outputSilentForMs = 30_000L,
            decoderSilentThroughoutOutput = true,
            recoveries = 0,
            msSinceLastRecovery = Long.MAX_VALUE,
        )
        assertEquals(AudioSilenceRecovery.Action.NONE, decision.action)
        assertEquals("decoder-silent-too", decision.reason)
    }

    @Test
    fun silenceShorterThanTheWindowIsLeftAlone() {
        val decision = AudioSilenceRecovery.decide(
            stalled = false,
            outputSilentForMs = AudioSilenceRecovery.OUTPUT_SILENT_MS - 1L,
            decoderSilentThroughoutOutput = false,
            recoveries = 0,
            msSinceLastRecovery = Long.MAX_VALUE,
        )
        assertEquals(AudioSilenceRecovery.Action.NONE, decision.action)
    }

    @Test
    fun aFrozenSinkStartsAtRebuildEvenBeforeTheSilentWindow() {
        val decision = AudioSilenceRecovery.decide(
            stalled = true,
            outputSilentForMs = 0L,
            decoderSilentThroughoutOutput = false,
            recoveries = 0,
            msSinceLastRecovery = Long.MAX_VALUE,
        )
        assertEquals(AudioSilenceRecovery.Action.RECOVER, decision.action)
        assertEquals(AudioSilenceRecovery.Step.REBUILD, decision.step)
    }

    @Test
    fun theLadderStopsAtItsLastRung() {
        val decision = AudioSilenceRecovery.decide(
            stalled = true,
            outputSilentForMs = 60_000L,
            decoderSilentThroughoutOutput = false,
            recoveries = AudioSilenceRecovery.MAX_STEPS,
            msSinceLastRecovery = Long.MAX_VALUE,
        )
        assertEquals(AudioSilenceRecovery.Action.NONE, decision.action)
        assertEquals("steps-exhausted", decision.reason)
    }

    @Test
    fun afterACrossfadeSettlesTheGainIsExactlyUnity() {
        // Both sides of the production fade end at 1/0, and a settled player
        // must never keep the outgoing side's zero.
        assertEquals(1f, CrossfadeGain.gains(1f).incoming, 0.0001f)
        assertEquals(0f, CrossfadeGain.gains(1f).outgoing, 0.0001f)
        assertEquals(1f, AudioSilenceRecovery.settledGain(transitionActive = false, current = 0f), 0f)
        assertEquals(1f, AudioSilenceRecovery.settledGain(transitionActive = false, current = 0.3f), 0f)
    }

    @Test
    fun duringATransitionTheFaderIsLeftWhereTheBlendPutIt() {
        assertEquals(0f, AudioSilenceRecovery.settledGain(transitionActive = true, current = 0f), 0f)
        assertEquals(0.42f, AudioSilenceRecovery.settledGain(transitionActive = true, current = 0.42f), 0f)
        assertEquals(1f, AudioSilenceRecovery.settledGain(transitionActive = true, current = 1.4f), 0f)
    }
}
