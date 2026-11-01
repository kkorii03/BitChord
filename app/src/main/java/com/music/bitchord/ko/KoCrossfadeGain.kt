package com.music.bitchord.ko

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * [KO] crossfade-gain
 *
 * The equal-power fade pair used by CrossfadeController's faders, kept in the
 * fork package so upstream's controller only calls into it.
 */
internal object KoCrossfadeGain {

    /**
     * Ramp for the armed incoming player when a skip before the handoff moves
     * the session onto the same row. It has to be longer than one bail tick
     * (15 ms) or the first sample lands at the blend gain and the next is
     * already past the span — the same step to `0f` the ramp exists to remove.
     * Three ticks is still far too short to hear the same row play twice
     * underneath.
     */
    const val SKIP_SILENCE_MS = 45L

    data class Gains(val incoming: Float, val outgoing: Float)

    fun gains(progress: Float): Gains {
        val p = progress.coerceIn(0f, 1f)
        return Gains(incoming = rise(p), outgoing = fall(p))
    }

    fun rise(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun fall(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /** The production incoming fader: every Automix style uses this pair. */
    fun mixRise(progress: Float): Float = rise(progress)

    /** The production outgoing fader: every Automix style uses this pair. */
    fun mixFall(progress: Float): Float = fall(progress)

    /**
     * The surviving player's gain while an interrupted blend is ramped away.
     *
     * An abort used to snap this side straight to unity, which was a jump of up
     * to nearly full scale when the fade was already well advanced. It now
     * returns from wherever the fader actually was, on the same short ramp the
     * leaving side uses.
     */
    fun bailSurvivor(fromGain: Float, progress: Float): Float {
        val from = fromGain.coerceIn(0f, 1f)
        return (from + (1f - from) * rise(progress)).coerceIn(0f, 1f)
    }

    /**
     * The armed incoming player's gain on a skip before the handoff: it has to
     * reach zero before the session plays the same queue row, but from a blend
     * gain that may be close to full scale. A `0f` assignment there was a step
     * discontinuity on every such skip; this returns the same shape of short
     * ramp the bail uses instead.
     */
    fun skipSilence(fromGain: Float, elapsedMs: Long, spanMs: Long): Float {
        val from = fromGain.coerceIn(0f, 1f)
        return (from * fall(progress(elapsedMs, spanMs))).coerceIn(0f, 1f)
    }


    /** [KO] crossfade-headroom — same smooth trim the controller applies mid-blend. */
    fun headroomFor(incomingGain: Float, outgoingGain: Float): Float {
        val sum = incomingGain + outgoingGain
        return if (sum <= 1f) 1f else 1f / kotlin.math.sqrt(sum)
    }

    fun progress(elapsedMs: Long, spanMs: Long): Float {
        if (spanMs <= 0L) return 1f
        return (elapsedMs.toDouble() / spanMs).toFloat().coerceIn(0f, 1f)
    }

    fun spanMs(plannedFadeMs: Long, incomingRemainingMs: Long?): Long {
        val incomingCap = incomingRemainingMs?.div(3) ?: Long.MAX_VALUE
        return minOf(plannedFadeMs, incomingCap).coerceAtLeast(1L)
    }
}
