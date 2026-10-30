package com.music.bitchord.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object CrossfadeGain {

    data class Gains(val incoming: Float, val outgoing: Float)

    fun gains(progress: Float): Gains {
        val p = progress.coerceIn(0f, 1f)
        return Gains(incoming = rise(p), outgoing = fall(p))
    }

    fun rise(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun fall(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun progress(elapsedMs: Long, spanMs: Long): Float {
        if (spanMs <= 0L) return 1f
        return (elapsedMs.toDouble() / spanMs).toFloat().coerceIn(0f, 1f)
    }

    fun spanMs(plannedFadeMs: Long, incomingRemainingMs: Long?): Long {
        val incomingCap = incomingRemainingMs?.div(3) ?: Long.MAX_VALUE
        return minOf(plannedFadeMs, incomingCap).coerceAtLeast(1L)
    }
}
