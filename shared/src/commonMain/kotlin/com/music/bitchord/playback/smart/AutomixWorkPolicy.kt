package com.music.bitchord.playback.smart

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
}
