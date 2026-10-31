package com.music.bitchord.data.net

import com.music.bitchord.data.MonotonicClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One rescue clock, shared by every rung that runs after a blocked start.
 *
 * The 10-31 report is the reason this exists: each stage had a timeout of its
 * own, but they were measured from different instants, so the upload retry,
 * the 14.9 s proxy walk and the extraction fallback all ran past the nine
 * seconds the listener had already spent. A stage now asks this clock how much
 * of the *whole* budget is left, and a result that arrives after the deadline
 * is discarded rather than handed to the player late.
 *
 * [stepTimeoutMs] is injectable so the order and the late-result rule can be
 * unit-tested without waiting nine real seconds.
 */
class BlockedStartRescue(
    private val startedAtMs: Long,
    private val nowMs: () -> Long = { MonotonicClock.nowMs() },
    private val totalBudgetMs: Long = BlockedStartPolicy.TOTAL_BUDGET_MS,
    private val stepTimeoutMs: (BlockedStartStep, Long) -> Long = { step, elapsed ->
        BlockedStartPolicy.timeoutFor(step, elapsed)
    },
) {
    val deadlineMs: Long = startedAtMs + totalBudgetMs

    fun elapsedMs(): Long = (nowMs() - startedAtMs).coerceAtLeast(0L)

    fun remainingMs(): Long = (deadlineMs - nowMs()).coerceAtLeast(0L)

    fun isExpired(): Boolean = nowMs() >= deadlineMs

    fun timeoutFor(step: BlockedStartStep): Long =
        stepTimeoutMs(step, elapsedMs()).coerceIn(0L, remainingMs())

    /**
     * Runs one rung inside whatever is left of the budget.
     *
     * Returns null when the rung has no time, fails, or answers after the
     * deadline. The last case matters on this path: an extraction or proxy
     * call that ignores cancellation can still finish late, and its stream is
     * exactly the one the report waited 32 s for. It is dropped here.
     */
    suspend fun <T : Any> runStep(
        step: BlockedStartStep,
        block: suspend (timeoutMs: Long) -> T?,
    ): T? {
        val timeout = timeoutFor(step)
        if (timeout <= 0L || isExpired()) return null
        val result = try {
            withTimeoutOrNull(timeout) { block(timeout) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        return result?.takeIf { !isExpired() }
    }
}
