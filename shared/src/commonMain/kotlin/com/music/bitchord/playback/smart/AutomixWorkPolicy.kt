package com.music.bitchord.playback.smart

import kotlin.math.abs

/**
 * Which part of a track Automix needs measured.
 *
 * A transition reads the outgoing track's tail and the incoming track's head;
 * the middle of either file is never evidence the planner uses. Measuring only
 * these windows keeps the analyzer's cost tied to the transition rather than to
 * the length of the song.
 */
enum class AnalysisWindow {
    /** The opening [AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS] seconds. */
    HEAD,

    /** The closing [AutomixWorkPolicy.TRANSITION_WINDOW_SECONDS] seconds. */
    TAIL,
}

/** One track and the window of it that still needs measuring. */
data class AnalysisTarget(val trackId: String, val window: AnalysisWindow)

/**
 * Battery policy for Automix's background work.
 *
 * Kept pure so the selection and timing rules can be tested without an
 * ExoPlayer, a decoder or an Android context. The caller supplies only what it
 * already knows: the queue pair, whether each side has a usable analysis, and
 * whether Automix is switched on.
 */
object AutomixWorkPolicy {

    /**
     * How much of each side of a transition is decoded. Long enough to cover a
     * phrase and an outro, short enough that a four-minute song is not read
     * end to end for a six-second blend.
     */
    const val TRANSITION_WINDOW_SECONDS = 45.0

    /**
     * How early the standby player is loaded. Before this point it holds no
     * media and opens no decoder; from it, the incoming track has enough time
     * to stand its decoder up and buffer to its cue.
     */
    const val STANDBY_LEAD_MS = 15_000L

    /**
     * The incoming side to measure next, or null when there is nothing to do.
     *
     * Only the immediate next track is considered — never the rest of the
     * queue — and an already-measured track is skipped, so the same song is not
     * decoded again every tick.
     */
    fun nextAnalysisTarget(
        nextId: String?,
        nextAnalysed: Boolean,
        automixEnabled: Boolean,
    ): AnalysisTarget? {
        if (!automixEnabled || nextAnalysed) return null
        if (nextId.isNullOrBlank()) return null
        return AnalysisTarget(nextId, AnalysisWindow.HEAD)
    }

    /**
     * The outgoing side to measure, or null when there is nothing to do.
     *
     * The current track's tail is only worth decoding when it has not been
     * measured yet. On a queue that has been playing for a while it was
     * already measured as the *next* track, so its opening is known; the tail
     * is the missing half that describes where the blend leaves it.
     */
    fun outgoingAnalysisTarget(
        currentId: String?,
        currentAnalysed: Boolean,
        automixEnabled: Boolean,
    ): AnalysisTarget? {
        if (!automixEnabled || currentAnalysed) return null
        if (currentId.isNullOrBlank()) return null
        return AnalysisTarget(currentId, AnalysisWindow.TAIL)
    }

    /**
     * Whether the standby should be armed now.
     *
     * [remainingMs] is the distance to the point the transition starts, not to
     * the end of the file: an analyzed mix-out anchor can put it well before the
     * track ends. [rampMs] is any tempo ramp that must finish first.
     */
    fun shouldArmStandby(
        remainingMs: Long,
        rampMs: Long = 0L,
        leadMs: Long = STANDBY_LEAD_MS,
    ): Boolean = remainingMs <= leadMs + rampMs.coerceAtLeast(0L)

    /** The lead used by the controller; zero when no transition can happen. */
    fun standbyLeadMs(transitionPossible: Boolean): Long =
        if (transitionPossible) STANDBY_LEAD_MS else 0L

    /**
     * Whether a pair must fall back to the plain crossfade because Automix is
     * not allowed to mix video songs.
     *
     * Only a row that is still a video counts. A song version converted from a
     * video is an ordinary audio row now, so it is mixed like any other song.
     */
    fun videoPairNeedsPlainCrossfade(
        automixVideos: Boolean,
        currentIsVideo: Boolean,
        nextIsVideo: Boolean,
    ): Boolean = !automixVideos && (currentIsVideo || nextIsVideo)

    /**
     * Whether the seek bar's Automix marker has enough behind it to be drawn.
     *
     * The incoming stream's cache is deliberately not part of this. The marker
     * describes where the plan puts the blend; waiting for the next track's
     * bytes hid it for most of the song under Direct Source Stream, because
     * nothing prefetches that track until this same plan opens the window.
     */
    fun markerVisible(
        blocked: Boolean,
        markerVisible: Boolean,
        durationMs: Long,
        currentAnalysed: Boolean,
        nextEnterable: Boolean,
    ): Boolean = !blocked && markerVisible && durationMs > 0L && currentAnalysed && nextEnterable

    /**
     * [EARLY_MIX_GUARD_MS] is the largest legitimate gap between where a smart
     * fade starts and the end of the outgoing file: an analyzed outro can pull
     * the start back, but three-quarters of a minute of song is a bad plan, not
     * a long outro. Tracks shorter than [MIN_GUARD_DURATION_MS] are exempt
     * because a genuine mix-out can sit anywhere on them.
     */
    const val EARLY_MIX_GUARD_MS = 30_000L
    const val MIN_GUARD_DURATION_MS = 60_000L

    /**
     * Whether a smart plan starts implausibly early for [durationMs].
     *
     * [fadeMs] is included so the rule is about where the blend begins relative
     * to the whole track: `start >= duration - fade - guard`. The guard exists
     * because a tail analysis can be published in its regional timeline instead
     * of the track's (content end ~24 s on a five-minute song), and arming from
     * that cuts the song off after a few seconds. Pure so the boundary can be
     * tested without a player.
     */
    fun smartArmTooEarly(
        transitionStartMs: Long,
        durationMs: Long,
        fadeMs: Long,
    ): Boolean {
        if (durationMs <= 0L || durationMs < MIN_GUARD_DURATION_MS) return false
        val earliest = durationMs - fadeMs.coerceAtLeast(0L) - EARLY_MIX_GUARD_MS
        return transitionStartMs < earliest
    }

    /**
     * How far an analysis's own duration may differ from the rendition now
     * playing before its timing is treated as belonging to another cut.
     */
    const val ANALYSIS_DURATION_TOLERANCE_SECONDS = 1.0

    /**
     * Whether [analysis] describes the same rendition as [durationSeconds].
     *
     * An analysis is stored per track, not per source. When playback switches
     * to a different rendition mid-song (a quality upgrade, or a substituted
     * source) the old run's duration and content end are on the old cut's
     * timeline. Arming a blend from those puts the mix minutes into the new
     * file — the 11-03 marker at 1:50 on a 2:53 track.
     */
    fun analysisDurationFits(analysis: TrackAnalysis, durationSeconds: Double): Boolean =
        windowFitsPlayback(analysis.duration, durationSeconds)

    /**
     * Whether a measurement taken at [measuredDurationSeconds] still describes
     * the rendition playing at [durationSeconds].
     *
     * Windows are keyed by track, so a mid-song source swap used to leave
     * `hasWindow` true for the old copy and the new rendition was never
     * re-analysed. An unknown duration on either side proves nothing and is
     * treated as a fit; a real disagreement means the measurement belongs to a
     * different cut and must be thrown away.
     */
    fun windowFitsPlayback(measuredDurationSeconds: Double, durationSeconds: Double): Boolean {
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return true
        if (!measuredDurationSeconds.isFinite() || measuredDurationSeconds <= 0.0) return true
        return abs(measuredDurationSeconds - durationSeconds) <= ANALYSIS_DURATION_TOLERANCE_SECONDS
    }

    /**
     * Whether a smart plan must be replaced by the plain end crossfade before
     * it is armed or drawn on the seek bar.
     *
     * The arming guard refuses a plan that starts implausibly early, so leaving
     * the smart plan in place would put the marker somewhere nothing can
     * happen. A blocked plan or a zero-length fade was never going to arm.
     */
    fun planNeedsPlainEndFallback(
        blocked: Boolean,
        fadeMs: Long,
        transitionStartMs: Long,
        durationMs: Long,
    ): Boolean = !blocked && fadeMs > 0L &&
        smartArmTooEarly(transitionStartMs, durationMs, fadeMs)


    /**
     * [analysisDurationFits] plus the tail window's own invariant: a content
     * end measured from the closing [TRANSITION_WINDOW_SECONDS] cannot sit
     * earlier than that window on the same rendition. One that does came from
     * another cut or a regional timeline, and is no evidence for this mix.
     */
    fun analysisFitsPlayback(analysis: TrackAnalysis, durationSeconds: Double): Boolean {
        if (!analysisDurationFits(analysis, durationSeconds)) return false
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return true
        val contentEnd = analysis.contentEndTime
        if (!contentEnd.isFinite() || contentEnd <= 0.0) return true
        val earliest = durationSeconds - TRANSITION_WINDOW_SECONDS - ANALYSIS_DURATION_TOLERANCE_SECONDS
        return contentEnd >= earliest && contentEnd <= durationSeconds + ANALYSIS_DURATION_TOLERANCE_SECONDS
    }
}
