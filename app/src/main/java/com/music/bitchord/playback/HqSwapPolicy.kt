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

    /** How long the incoming side gets to actually start before the blend is abandoned. */
    const val START_TIMEOUT_MS = 600L

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
     * @param versionSwapActive whether a song-version swap is already driving the
     *   same spare player. Both fades rebuild the standby from the session
     *   player's queue, so starting one while the other is running would have
     *   the loser prepare the spare and then have it torn down underneath it.
     * @param sameTrack whether the item the search was started for is still current.
     * @param offsetMs the verified `candidateTime - youtubeTime`, from [HqMatchPolicy].
     * @param rate the verified candidate seconds per YouTube second.
     * @param pinned whether the listener has held this track on the original.
     *   The revert can land while a search is already running, and that search
     *   neither knows nor can be told; the pin has the last word.
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
        pinned: Boolean = false,
        versionSwapActive: Boolean = false,
        videoRadio: Boolean = false,
        isVideo: Boolean = false,
    ): Decision {
        if (!sameTrack) return Decision(false, "track-changed", 0L)
        if (pinned) return Decision(false, "pinned", 0L)
        if (crossfadeActive) return Decision(false, "crossfade", 0L)
        if (versionSwapActive) return Decision(false, "version-swap", 0L)
        // A video-radio row is kept as the upload the listener chose; the
        // mid-song lossless path must refuse it just as the next-track step
        // does, or a catalogue FLAC replaces the upload's own sound.
        if (videoRadio || isVideo) return Decision(false, "video-radio", 0L)
        if (!rate.isFinite() || rate <= 0.0) return Decision(false, "bad-rate", 0L)
        // The current substitute's clock has to be usable before it can be
        // mapped back to YouTube time. A corrupt rate would otherwise let the
        // raw position stand in and seek the replacement to the wrong offset.
        if (!currentRate.isFinite() || currentRate <= 0.0) {
            return Decision(false, "bad-current-rate", 0L)
        }
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
     * Whether a song-version swap may take the spare player.
     *
     * The crossfade term is the long-standing rule. The lossless term is the
     * same rule for the newer mid-song swap: it owns the spare from the moment
     * it starts preparing until it lands or gives up, so a version swap that
     * ran beside it used to stop/clear that standby mid-swap.
     */
    fun versionSwapMayStart(
        crossfadeActive: Boolean,
        losslessSwapActive: Boolean,
    ): Boolean = !crossfadeActive && !losslessSwapActive

    /**
     * Whether any transition currently owns the active/standby pair.
     *
     * The output-silence monitor must stand down while this is true: a crossfade
     * or either swap deliberately fades one side to near zero, and judging that
     * mid-fade gain as a fault made [PlaybackService.recoverAudibleGains] raise
     * it again, so the two fades played over each other. The lossless term has
     * to cover the whole prepare+blend window ([hqSwapOwnsStandby]), not only
     * the instant the new media id is published.
     */
    fun transitionOwnsPlayer(
        crossfadeActive: Boolean,
        versionSwapActive: Boolean,
        losslessSwapActive: Boolean,
    ): Boolean = crossfadeActive || versionSwapActive || losslessSwapActive

    /**
     * Whether the item a swap was started for is still the one in the player.
     *
     * The decision in [decide] is made before the replacement is prepared, and
     * preparing plus blending can take seconds. A revert during that window
     * pins the track and replaces the item, but neither changes its media id,
     * index or player, so the old identity checks could not see it. The pin and
     * the item's URI are read again at the last moment before the swap is
     * adopted or reverted.
     */
    fun stillOwnsItem(
        pinned: Boolean,
        currentUri: String?,
        expectedUri: String?,
    ): Boolean = !pinned && currentUri == expectedUri

    /**
     * Whether the replacement proved itself against the length the old copy
     * already knew.
     *
     * [proven] is the event-driven wait's answer. A wait that expired without
     * reaching its condition is a failure even if a duration happens to be
     * readable afterwards: the old loop reverted on timeout, and treating a
     * late-arriving duration as proof would drop that revert. The pin/URI
     * bail-outs are checked before this, so a listener's own revert is never
     * mistaken for a short stream.
     */
    fun provesDuration(
        proven: Boolean,
        durationMs: Long,
        expectedDurationMs: Long,
        slackMs: Long,
    ): Boolean = proven &&
        durationMs > 0L &&
        kotlin.math.abs(durationMs - expectedDurationMs) <= slackMs

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
