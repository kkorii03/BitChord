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
    const val ALTERNATE_TIMEOUT_MS = 2_000L

    /** The overseas player request and its media probe. */
    const val PROXY_TIMEOUT_MS = 2_500L

    /**
     * A high-quality source when YouTube has nothing. The first note is not
     * held for `hq-verify`; that comparison still runs afterwards, on the
     * existing rules, and can refuse the copy while it plays.
     */
    const val HIGH_QUALITY_TIMEOUT_MS = 3_000L

    /** The steps worth attempting, in the order they are attempted. */
    fun plan(proxyAvailable: Boolean, highQualityAvailable: Boolean): List<BlockedStartBudget> =
        buildList {
            add(BlockedStartBudget(BlockedStartStep.ALTERNATE, ALTERNATE_TIMEOUT_MS))
            if (proxyAvailable) add(BlockedStartBudget(BlockedStartStep.PROXY, PROXY_TIMEOUT_MS))
            if (highQualityAvailable) {
                add(BlockedStartBudget(BlockedStartStep.HIGH_QUALITY, HIGH_QUALITY_TIMEOUT_MS))
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
     */
    fun timeoutFor(step: BlockedStartStep, elapsedMs: Long): Long {
        val remaining = (TOTAL_BUDGET_MS - elapsedMs.coerceAtLeast(0L)).coerceIn(0L, TOTAL_BUDGET_MS)
        val wanted = when (step) {
            BlockedStartStep.DIRECT -> DIRECT_HOLD_MS
            BlockedStartStep.ALTERNATE -> ALTERNATE_TIMEOUT_MS
            BlockedStartStep.PROXY -> PROXY_TIMEOUT_MS
            BlockedStartStep.HIGH_QUALITY -> HIGH_QUALITY_TIMEOUT_MS
            BlockedStartStep.NEXT_TRACK -> 0L
        }
        return wanted.coerceAtMost(remaining)
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
