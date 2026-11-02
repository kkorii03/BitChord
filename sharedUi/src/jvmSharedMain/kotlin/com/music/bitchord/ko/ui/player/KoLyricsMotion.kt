// Ported in part from AMLL (applemusic-like-lyrics, AGPL-3.0, (c) amll-dev contributors)
package com.music.bitchord.ko.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The lyric motion numbers AMLL uses, kept here so they can be checked without
 * a Compose frame. Nothing in this file touches state or the clock.
 */

/** A word's feather is half a full-width character of font height (AMLL 0.5). */
internal const val KO_WORD_FEATHER_RATIO = 0.5f

internal fun koWordFeatherPx(fontHeightPx: Float, ratio: Float = KO_WORD_FEATHER_RATIO): Float =
    fontHeightPx * ratio.coerceIn(0.5f, 1f)

/** AMLL only treats a break as an interlude with a body from 7 s. */
internal const val KO_INTERLUDE_BREATH_MIN_MS = 7_000L
internal const val KO_INTERLUDE_BREATH_PERIOD_MS = 4_000L
internal const val KO_INTERLUDE_MIN_BODY_MS = 3_000L

internal fun koInterludeBreathes(gapMs: Long): Boolean =
    gapMs >= KO_INTERLUDE_BREATH_MIN_MS

/**
 * The whole dot row's breath, 0.4..1.25, one cycle every 4 s. A break too short
 * for a whole cycle holds at rest instead (AMLL's fallback-hold).
 */
internal fun koInterludeBreathScale(elapsedMs: Long, gapMs: Long): Float {
    if (!koInterludeBreathes(gapMs) || gapMs < KO_INTERLUDE_MIN_BODY_MS) return 1f
    val period = KO_INTERLUDE_BREATH_PERIOD_MS.toFloat()
    val phase = (((elapsedMs % KO_INTERLUDE_BREATH_PERIOD_MS) + KO_INTERLUDE_BREATH_PERIOD_MS) %
        KO_INTERLUDE_BREATH_PERIOD_MS) / period
    val wave = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)
    return 0.4f + 0.85f * wave
}

/** Staged entry: container 180 ms, dots 750 ms each, 80 ms apart. */
internal const val KO_INTERLUDE_CONTAINER_IN_MS = 180L
internal const val KO_INTERLUDE_DOT_IN_MS = 750L
internal const val KO_INTERLUDE_DOT_STAGGER_MS = 80L

internal fun koInterludeContainerAlpha(sinceMs: Long): Float =
    (sinceMs / KO_INTERLUDE_CONTAINER_IN_MS.toFloat()).coerceIn(0f, 1f)

internal fun koInterludeDotEntryAlpha(dot: Int, sinceMs: Long): Float =
    ((sinceMs - dot * KO_INTERLUDE_DOT_STAGGER_MS) / KO_INTERLUDE_DOT_IN_MS.toFloat())
        .coerceIn(0f, 1f)

/** The last 250 ms of a break fade out. */
internal const val KO_INTERLUDE_EXIT_FADE_MS = 250L

internal fun koInterludeExitAlpha(remainingMs: Long): Float =
    (remainingMs / KO_INTERLUDE_EXIT_FADE_MS.toFloat()).coerceIn(0f, 1f)

/** AMLL's line hierarchy: the active line sits at 0.85, the others step back. */
internal const val KO_ACTIVE_LINE_ALPHA = 0.85f
internal const val KO_INACTIVE_SCALE = 0.97f

/** Blur is 1 + distance from the focus row, capped at 5 px, 0.8 on a phone. */
internal const val KO_LINE_BLUR_CAP_PX = 5f
internal const val KO_NARROW_BLUR_SCALE = 0.8f

internal fun koLineBlurDp(distance: Int, narrow: Boolean): Dp {
    if (distance <= 0) return 0.dp
    val raw = distance.toFloat() * if (narrow) KO_NARROW_BLUR_SCALE else 1f
    return raw.coerceAtMost(KO_LINE_BLUR_CAP_PX).dp
}

/** The cascade: the first row starts now, each later one 6% of the run behind. */
internal const val KO_STAGGER_STEPS = 3
internal const val KO_STAGGER_FRACTION = 0.06f

internal fun koStaggerDelayMs(behind: Int, durationMs: Int): Float =
    behind.coerceIn(0, KO_STAGGER_STEPS) * KO_STAGGER_FRACTION * durationMs

/**
 * AMLL's scroll spring: stiffness 90 and damping 15 with no usable interval,
 * otherwise 170..220 with damping sqrt(k) * 2.2 (which is a damping ratio of
 * 1.1). The ratio is what Compose's spring takes, not the damping coefficient.
 */
internal data class KoLyricSpring(val stiffness: Float, val dampingRatio: Float)

internal val KO_SLOW_LYRIC_SPRING = KoLyricSpring(90f, 15f / (2f * sqrt(90f)))

internal fun koLyricSpringForInterval(intervalMs: Long?): KoLyricSpring {
    if (intervalMs == null || intervalMs !in 100..800) return KO_SLOW_LYRIC_SPRING
    val ratio = (1f - (intervalMs - 100f) / 700f).coerceIn(0f, 1f).pow(0.2f)
    val stiffness = 170f + ratio * 50f
    return KoLyricSpring(stiffness, 1.1f)
}
