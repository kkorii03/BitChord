package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.sources.HqMatchPolicy
import kotlin.math.abs
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

    /** How far the standby may sit from the session's playhead at the handover. */
    const val POSITION_SLACK_MS = 1_000L

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
     * @param suppressed whether the session player wants to play but is being
     *   held back, as a call does. `playWhenReady` stays true through that and
     *   the standby holds no audio focus, so it would play over the call.
     * @param currentClockKnown false when a substitute is playing with no
     *   verified offset. The candidate's offset is measured against the YouTube
     *   clock, so the playhead cannot be mapped across and a seek would land on
     *   the wrong audio; the copy waits for the next play instead. Asked again
     *   after the download, which can outlast the verdict that was read before.
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
        suppressed: Boolean = false,
        currentClockKnown: Boolean = true,
    ): Decision {
        if (!sameTrack) return Decision(false, "track-changed", 0L)
        if (pinned) return Decision(false, "pinned", 0L)
        if (crossfadeActive) return Decision(false, "crossfade", 0L)
        if (versionSwapActive) return Decision(false, "version-swap", 0L)
        if (suppressed) return Decision(false, "suppressed", 0L)
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
        if (!currentClockKnown) return Decision(false, "current-clock-unknown", 0L)
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
     * Whether a swap that is ending still owes its standby a release.
     *
     * Adoption is what makes the standby the session's player, and it is also
     * what moves the outgoing player into `spare`. A swap that ends any other
     * way — a bail-out, or a cancellation while the standby is preparing or
     * mid-blend — leaves that player armed and running: it keeps decoding the
     * outgoing song at zero volume, holding a decoder and the network open,
     * and the outgoing player keeps the gain the blend left on it. Both swap
     * jobs ask this from their `finally`.
     */
    fun standbyMustBeReleased(armed: Boolean, adopted: Boolean): Boolean = armed && !adopted

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

    /**
     * Why the prepared standby must not be blended in or adopted; null when it
     * still may.
     *
     * "READY" and "playing" say nothing about which song. A replacement shorter
     * than the playhead reads past its own end and ExoPlayer advances the queue,
     * so the standby reports itself ready and playing on the next row. Adopting
     * that hands the session to the wrong song, and [PlaybackService.watchUpgrade]
     * cannot revert it because the item is no longer this track. A standby that
     * failed or ended during the blend would be adopted as silence.
     *
     * [suppressed] is the session player's own state: only that player holds
     * audio focus, so during a call this is the one thing telling the standby
     * not to play over it.
     *
     * @param standbyDurationMs non-positive while the length is not known yet,
     *   which is not evidence of a short file.
     */
    fun standbyUnfitReason(
        expectedMediaId: String,
        expectedIndex: Int,
        standbyMediaId: String?,
        standbyIndex: Int,
        standbyState: Int,
        standbyPositionMs: Long,
        standbyDurationMs: Long,
        suppressed: Boolean,
    ): String? = when {
        suppressed -> "suppressed"
        standbyMediaId != expectedMediaId || standbyIndex != expectedIndex -> "standby-moved"
        standbyState == Player.STATE_IDLE || standbyState == Player.STATE_ENDED -> "standby-stopped"
        standbyDurationMs > 0L && standbyDurationMs <= standbyPositionMs -> "standby-too-short"
        else -> null
    }

    /**
     * Whether adopting the standby would audibly move the song. Well above the
     * gap a blend leaves between the two sides, so in practice only a seek by the
     * listener during the swap trips it.
     */
    fun standbyOffTarget(standbyPositionMs: Long, targetMs: Long): Boolean =
        abs(standbyPositionMs - targetMs) > POSITION_SLACK_MS

    /**
     * Whether a swap that started paused has lost its place. That path never
     * seeks its standby again, unlike the playing path, so play or a seek during
     * the prepare would send the song back to where it was when the swap began.
     * The swap gives up rather than adding a seek and its wait to this path.
     */
    fun pausedStandbyStale(
        activePlayWhenReady: Boolean,
        standbyPositionMs: Long,
        targetMs: Long,
    ): Boolean = activePlayWhenReady || standbyOffTarget(standbyPositionMs, targetMs)

    data class QueueEdits(val head: Boolean, val tail: Boolean)

    /**
     * Which side of the current row the listener changed while the standby was
     * being prepared from a copy of the queue. Up to eight seconds pass between
     * that copy and the handover, and adopting the copy as it stands would drop
     * a song added in that time or bring back one removed. The current row is
     * skipped: it differs on purpose, being the upgraded item.
     */
    fun queueEdits(
        activeKeys: List<String>,
        standbyKeys: List<String>,
        currentIndex: Int,
    ): QueueEdits = QueueEdits(
        head = activeKeys.take(currentIndex) != standbyKeys.take(currentIndex),
        tail = activeKeys.drop(currentIndex + 1) != standbyKeys.drop(currentIndex + 1),
    )

    /** The candidate's own position for [youtubeMs], using the verified offset and rate. */
    fun targetPositionMs(youtubeMs: Long, offsetMs: Long, rate: Double): Long =
        HqMatchPolicy.applyOffset(youtubeMs, offsetMs, rate)

    /**
     * Where a copy verified as [offsetMs]/[rate] has to open, given
     * [positionMs] on the copy playing now.
     *
     * Back onto YouTube time through the outgoing verdict, then forward through
     * the incoming one. A mid-song swap that seeked to the raw position skipped
     * both and moved the song by the difference — up to the ninety seconds a
     * match tolerates. See [PlaybackService.swapIn].
     */
    fun swapStartMs(
        positionMs: Long,
        currentOffsetMs: Long,
        currentRate: Double,
        offsetMs: Long,
        rate: Double,
    ): Long = targetPositionMs(toYoutubeMs(positionMs, currentOffsetMs, currentRate), offsetMs, rate)
}
