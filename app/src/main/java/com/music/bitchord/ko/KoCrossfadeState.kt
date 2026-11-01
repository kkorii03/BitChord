package com.music.bitchord.ko

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.channels.Channel

/**
 * [KO] crossfade-state
 *
 * Fork-only state for the queue handoff, event-driven ticker, arm pause/skip
 * bookkeeping, loudness-match bookkeeping and bail ramp. Upstream's controller
 * holds only its own fields.
 */
internal class KoCrossfadeState {
    /** The queue index the incoming track occupied when the standby was loaded. */
    var incomingItemIndex = C.INDEX_UNSET

    /** Whether the transition wraps from the last queue row to the first. */
    var incomingWrapsToStart = false

    /** Wakes the tick early when an event made the next step due now. */
    val tickerWake = Channel<Unit>(Channel.CONFLATED)

    /** A plain fade Automix ran for a video pair with the slider at zero. */
    var automixFallbackFade = false

    /** When driveFade first reached progress 1. */
    var fadeCompletedAt = 0L
    var lastFadeProgress = 0f
    var lastFadeQuarter = 0
    val fadeQuarterGains = arrayOfNulls<String>(3)

    /** When the current arming attempt started, for the duration log. */
    var armStartedAt = 0L

    /** When the listener paused an armed pair, or 0 when it is running. */
    var armPausedAt = 0L

    /** The pair already refused for this outgoing track. */
    var skippedPair: Pair<String?, String?>? = null

    /** A skip before the handoff ramps the armed incoming player down. */
    var bailSilenceLeaving = false
    var skipSilenceStartedAt = 0L

    /** The session-side player during a bail, and the faded gain it starts from. */
    var bailSurvivor: ExoPlayer? = null
    var bailSurvivorFromGain = 1f
}
