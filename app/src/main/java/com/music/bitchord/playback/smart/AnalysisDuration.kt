package com.music.bitchord.playback.smart

/**
 * Shortest track worth measuring for a blend, in seconds.
 *
 * A transition on a track this short is over before its own analysis could
 * describe it, and the whole-track pass is the most expensive background work
 * the app does (a full decode plus two ONNX passes). Nothing below this is
 * mixed.
 */
internal const val MIN_ANALYSIS_SECONDS = 60.0

/**
 * Whether a track of [durationSeconds] is worth queueing for analysis.
 *
 * A non-positive duration is *unknown*, not short: it is what a queue row
 * without a stated runtime reports before Media3 has loaded the window, and
 * such a row is re-requested on every tick. Dropping it here would mean an
 * unmeasured incoming track never gets a chance to become measured once its
 * real duration arrives; deciding "short" on a number nobody has yet would
 * have the opposite mistake. Callers therefore re-evaluate, and a track that
 * turns out to be short stops on the tick its duration is known.
 */
internal fun trackLongEnoughToAnalyse(durationSeconds: Double): Boolean =
    durationSeconds <= 0.0 || durationSeconds >= MIN_ANALYSIS_SECONDS
