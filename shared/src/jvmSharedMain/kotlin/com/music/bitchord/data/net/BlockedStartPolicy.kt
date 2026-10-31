package com.music.bitchord.data.net

/**
 * The order and the time limits for starting a track YouTube refuses here.
 *
 * A region/Premium refusal is not a question that gets better with waiting:
 * the reported start spent 53 s inside one walk before a catalogue copy was
 * allowed to make a sound. This policy bounds each rescue so the listener
 * hears something — the normal copy, the proxy, a high-quality recording, or
 * the next row — within [TOTAL_BUDGET_MS].
 *
 * The steps are deliberately ordered the way the listener expects them:
 * another normal upload of the same recording first, then the overseas proxy,
 * then a high-quality source (played while the existing `hq-verify` pass runs
 * in the background), and only then the next queue row. Nothing here polls:
 * each caller runs one step and moves on when it answers or its deadline
 * expires.
 */
enum class BlockedStartStep {
    DIRECT,
    ALTERNATE,
    PROXY,
    HIGH_QUALITY,
    NEXT_TRACK,
}

/** One rescue and the most time it may spend before the next one starts. */
data class BlockedStartBudget(val step: BlockedStartStep, val timeoutMs: Long)

object BlockedStartPolicy {
    /** The whole rescue, from the first refusal to the next row making a sound. */
    const val TOTAL_BUDGET_MS = 10_000L

    /**
     * How long a direct client's refusal is allowed to sit beside InnerTubeX
     * before the proxy is started. The direct answer lands in ~300 ms on this
     * device; nine seconds was the walk that produced the report.
     */
    const val DIRECT_HOLD_MS = 1_200L

    /** Another normal upload of the same recording: one bounded catalogue ask. */
    const val ALTERNATE_TIMEOUT_MS = 1_500L

    /** The overseas player request and its media probe. */
    const val PROXY_TIMEOUT_MS = 2_500L

    /**
     * A high-quality source when YouTube has nothing. The first note is not
     * held for `hq-verify`; that comparison still runs afterwards, on the
     * existing rules, and can refuse the copy while it plays.
     */
    const val HIGH_QUALITY_TIMEOUT_MS = 4_000L

    /**
     * The steps worth attempting, in the order they are attempted.
     *
     * Walked with [nextStep] rather than written out by hand: the order is the
     * one thing a caller and this policy must agree on, and a separate list
     * would let a later edit reorder one without the other.
     */
    fun plan(proxyAvailable: Boolean, highQualityAvailable: Boolean): List<BlockedStartBudget> =
        buildList {
            var step = nextStep(BlockedStartStep.DIRECT)
            while (step != null && step != BlockedStartStep.NEXT_TRACK) {
                val timeout = when (step) {
                    BlockedStartStep.ALTERNATE -> ALTERNATE_TIMEOUT_MS
                    BlockedStartStep.PROXY -> PROXY_TIMEOUT_MS.takeIf { proxyAvailable }
                    BlockedStartStep.HIGH_QUALITY -> HIGH_QUALITY_TIMEOUT_MS.takeIf {
                        highQualityAvailable
                    }
                    else -> null
                }
                if (timeout != null) add(BlockedStartBudget(step, timeout))
                step = nextStep(step)
            }
            add(BlockedStartBudget(BlockedStartStep.NEXT_TRACK, 0L))
        }

    /** The next step in the fixed order, or null after [BlockedStartStep.NEXT_TRACK]. */
    fun nextStep(step: BlockedStartStep): BlockedStartStep? = when (step) {
        BlockedStartStep.DIRECT -> BlockedStartStep.ALTERNATE
        BlockedStartStep.ALTERNATE -> BlockedStartStep.PROXY
        BlockedStartStep.PROXY -> BlockedStartStep.HIGH_QUALITY
        BlockedStartStep.HIGH_QUALITY -> BlockedStartStep.NEXT_TRACK
        BlockedStartStep.NEXT_TRACK -> null
    }

    /**
     * The time [step] may still spend, never more than what is left of the
     * whole rescue. A spent budget is zero, which means try the next step now
     * rather than start work that can only outlive the listener's patience.
     *
     * Except the high-quality search: it is the rung that actually saved the
     * report's track (10-31 Betty, a FLAC from Qobuz), so it always gets its own
     * window, and the alternate ahead of it leaves that window free instead of
     * eating the last second and sending a playable track to the skip.
     */
    fun timeoutFor(step: BlockedStartStep, elapsedMs: Long): Long {
        val remaining = (TOTAL_BUDGET_MS - elapsedMs.coerceAtLeast(0L)).coerceIn(0L, TOTAL_BUDGET_MS)
        return when (step) {
            BlockedStartStep.DIRECT -> DIRECT_HOLD_MS.coerceAtMost(remaining)
            BlockedStartStep.ALTERNATE -> ALTERNATE_TIMEOUT_MS.coerceAtMost(
                (remaining - HIGH_QUALITY_TIMEOUT_MS).coerceAtLeast(0L),
            )
            BlockedStartStep.PROXY -> PROXY_TIMEOUT_MS.coerceAtMost(remaining)
            BlockedStartStep.HIGH_QUALITY -> HIGH_QUALITY_TIMEOUT_MS
            BlockedStartStep.NEXT_TRACK -> 0L
        }
    }

    /**
     * Whether a direct refusal should start the proxy beside InnerTubeX now.
     *
     * Keeping InnerTubeX in front of an ordinary refusal is deliberate (a
     * learned path must not lose the lead), but a *playing* direct client may
     * not hold the track silent for the thirteen-second walk. The same
     * truth table as before, with the elapsed-time term added:
     *
     *  - direct not refused            -> false (nothing to escalate)
     *  - refused, InnerTubeX finished  -> false (its answer is already in hand)
     *  - refused, InnerTubeX running, under the hold -> false
     *  - refused, InnerTubeX running, at/over the hold -> true
     */
    fun shouldStartProxyEarly(
        directRefused: Boolean,
        innerTubeXStillRunning: Boolean,
        elapsedMs: Long,
    ): Boolean = directRefused && innerTubeXStillRunning && elapsedMs >= DIRECT_HOLD_MS

    /**
     * Whether the high-quality source is the next step after the alternate
     * lookup. This is the same condition the playback rescue used before the
     * policy existed, kept as one truth table so a later change cannot let a
     * different recording or a truly gone upload become a catalogue search:
     *
     *  - an alternate was already found            -> false (it plays first)
     *  - the row must keep its recording           -> false (mid-song failure)
     *  - the failure is the network, not the track -> false (retry, not swap)
     *  - the upload itself is gone                 -> false (skip)
     *  - otherwise                                 -> true
     */
    fun shouldSearchHighQuality(
        alternateFound: Boolean,
        allowAlternateRecording: Boolean,
        networkFault: Boolean,
        definitelyGone: Boolean,
    ): Boolean = !alternateFound && allowAlternateRecording && !networkFault && !definitelyGone

    /**
     * Whether the slow extraction failsafe should be skipped after both the
     * direct ladder and the proxy refused. Extraction reads the same watch
     * page and cannot answer a region or Premium gate; spending its attempts
     * is the silence this policy exists to cut.
     */
    fun shouldSkipExtraction(directRefused: Boolean, proxyFailed: Boolean): Boolean =
        directRefused && proxyFailed

    /** The one line every stage writes, in the format the device logs are read by. */
    fun logLine(mediaId: String, step: BlockedStartStep, result: String, ms: Long): String =
        "blocked-start: $mediaId step=${step.name.lowercase()} result=$result ms=${ms.coerceAtLeast(0L)}"
}
