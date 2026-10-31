package com.music.bitchord.playback.audio

/**
 * Decides whether non-advancing or all-zero output is a fault and, if so, how
 * far up the recovery ladder to climb.
 *
 * The distinction this exists for is the one the old output-health check could
 * not make: a song's own quiet passage and a broken output path both look like
 * zeroes arriving. The decoder-level meter is the tie-breaker. If the decoder
 * has been producing samples while the post-gain block stayed at zero, the
 * chain between them (a fader, a filter, a stale AudioTrack) is at fault. If
 * the decoder itself has been silent for the whole window, the file is quiet
 * and recovery must not touch it.
 *
 * Pure on purpose: the service's existing progress sampler calls [decide], and
 * the ladder can be unit-tested without an Android audio device.
 */
object AudioSilenceRecovery {

    /** A sample magnitude below this is silence for the purposes of recovery. */
    const val SILENT_PEAK = 1e-4f

    /** A sink that has not accepted a buffer for this long is stalled. */
    const val OUTPUT_STALL_MS = 4_000L

    /** Frames arriving, but all zero, for this long is a silent output path. */
    const val OUTPUT_SILENT_MS = 12_000L

    /** Time a recovery step gets to prove itself before the next one is tried. */
    const val STEP_RECHECK_MS = 5_000L

    /** rebuild -> reprepare -> reresolve. Three steps, then leave the track be. */
    const val MAX_STEPS = 3

    /** The gain a player must carry once a transition has settled. */
    const val SETTLED_GAIN = 1f

    enum class Step(val wire: String) {
        REBUILD("rebuild"),
        REPREPARE("reprepare"),
        RERESOLVE("reresolve"),
    }

    enum class Action { NONE, RECOVER }

    data class Decision(
        val action: Action,
        val step: Step? = null,
        val reason: String,
    )

    /** The next rung after [recoveries] steps have already been spent. */
    fun nextStep(recoveries: Int): Step = when {
        recoveries <= 0 -> Step.REBUILD
        recoveries == 1 -> Step.REPREPARE
        else -> Step.RERESOLVE
    }

    /**
     * @param stalled the sink stopped accepting buffers, not merely turned them
     *   into zeroes.
     * @param outputSilentForMs how long the post-gain block has been zero. A
     *   value of 0 means output is currently audible.
     * @param decoderSilentThroughoutOutput true when the decoder itself went
     *   quiet no later than the output did — the file is genuinely silent, not
     *   a broken gain stage. False means the decoder was still producing
     *   samples after the output went to zero.
     * @param recoveries how many steps this track has already spent.
     * @param msSinceLastRecovery time since the previous step, or a value at or
     *   above [STEP_RECHECK_MS] when none has run.
     */
    fun decide(
        stalled: Boolean,
        outputSilentForMs: Long,
        decoderSilentThroughoutOutput: Boolean,
        recoveries: Int,
        msSinceLastRecovery: Long,
    ): Decision {
        if (recoveries >= MAX_STEPS) {
            return Decision(Action.NONE, reason = "steps-exhausted")
        }
        if (stalled) {
            return Decision(Action.RECOVER, nextStep(recoveries), "stalled")
        }
        if (outputSilentForMs < OUTPUT_SILENT_MS) {
            return Decision(Action.NONE, reason = "short")
        }
        // A step in flight gets its own window to bring sound back; the next
        // tick must not immediately jump another rung before then.
        if (recoveries > 0 && msSinceLastRecovery < STEP_RECHECK_MS) {
            return Decision(Action.NONE, reason = "waiting-for-step")
        }
        // The decoder was already quiet when the output went quiet: this is the
        // track's own silence, not a broken fader or AudioTrack.
        if (decoderSilentThroughoutOutput) {
            return Decision(Action.NONE, reason = "decoder-silent-too")
        }
        return Decision(Action.RECOVER, nextStep(recoveries), "silent-output")
    }

    /**
     * The gain to leave a player at once a transition is over. During a blend a
     * side may legitimately be at any fader value, including zero; after it,
     * zero is always a defect rather than a fade.
     */
    fun settledGain(transitionActive: Boolean, current: Float): Float =
        if (transitionActive) current.coerceIn(0f, 1f) else SETTLED_GAIN
}
