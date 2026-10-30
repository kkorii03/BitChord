package com.music.bitchord.playback

import com.music.bitchord.data.sources.HqMatchPolicy
import kotlin.math.roundToLong

/**
 * The pure half of "may the verified lossless copy replace the one playing
 * right now".
 *
 * The decision is deliberately all here so it can be tested without a player:
 * the service reads the live position, duration and crossfade state, and this
 * object says whether cutting into the track is worth it and where the
 * replacement should start.
 */
internal object HqSwapPolicy {

    /** How much of the track has to be left for the interruption to be worth it. */
    const val MIN_REMAINING_MS = 20_000L

    /** The short equal-power blend used for this swap; the same recording is on both sides. */
    const val FADE_MS = 250L

    /** How long the spare player gets to open and buffer the replacement. */
    const val PREPARE_TIMEOUT_MS = 7_000L
    const val PREPARE_STEP_MS = 20L

    /** How long the incoming side gets to actually start before the blend is abandoned. */
    const val START_TIMEOUT_MS = 600L
    const val START_STEP_MS = 10L

    /** The blend is driven by the clock, in the same shape as the version swap. */
    const val FADE_STEP_MS = 16L

    data class Decision(
        val allowed: Boolean,
        /** Why the swap was refused; empty when [allowed]. */
        val reason: String,
        /** Where the replacement opens, in its own clock. */
        val targetPositionMs: Long,
    )

    /**
     * @param positionMs where the outgoing copy is now.
     * @param durationMs the outgoing track's length, or a non-positive value
     *   when the player has not reported one yet.
     * @param crossfadeActive whether a transition currently owns the spare player.
     * @param sameTrack whether the item the search was started for is still current.
     * @param offsetMs the verified `candidateTime - youtubeTime`, from [HqMatchPolicy].
     * @param rate the verified candidate seconds per YouTube second.
     */
    fun decide(
        positionMs: Long,
        durationMs: Long,
        crossfadeActive: Boolean,
        sameTrack: Boolean,
        offsetMs: Long,
        rate: Double,
        currentOffsetMs: Long = 0L,
        currentRate: Double = 1.0,
    ): Decision {
        if (!sameTrack) return Decision(false, "track-changed", 0L)
        if (crossfadeActive) return Decision(false, "crossfade", 0L)
        val remaining = durationMs.takeIf { it > 0L }?.minus(positionMs)
        if (remaining != null && remaining < MIN_REMAINING_MS) {
            return Decision(false, "too-late", 0L)
        }
        val youtubeMs = toYoutubeMs(positionMs, currentOffsetMs, currentRate)
        val target = targetPositionMs(youtubeMs, offsetMs, rate)
        if (target < 0L || (durationMs > 0L && target >= durationMs)) {
            return Decision(false, "position-out-of-range", target)
        }
        return Decision(true, "", target)
    }

    /**
     * The YouTube clock for a position on the copy that is playing now.
     *
     * A YouTube stream is already on that clock. A verified substitute is not:
     * its own verdict says `candidate = offset + rate × youtube`, so the
     * inverse puts the playhead back where the new candidate's offset applies.
     */
    fun toYoutubeMs(candidateMs: Long, offsetMs: Long, rate: Double): Long {
        if (!rate.isFinite() || rate <= 0.0) return candidateMs
        return ((candidateMs - offsetMs) / rate).roundToLong().coerceAtLeast(0L)
    }

    /** The candidate's own position for [youtubeMs], using the verified offset and rate. */
    fun targetPositionMs(youtubeMs: Long, offsetMs: Long, rate: Double): Long =
        HqMatchPolicy.applyOffset(youtubeMs, offsetMs, rate)
}
