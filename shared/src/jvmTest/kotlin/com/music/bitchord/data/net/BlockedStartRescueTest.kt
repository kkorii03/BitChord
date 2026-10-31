package com.music.bitchord.data.net

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The blocked-start rescue with fake, deliberately slow resolvers.
 *
 * These use a shortened budget and step window rather than waiting nine real
 * seconds; the decision being tested is the same one the phone makes — the
 * whole rescue ends at its deadline, a stream that arrives after it is not
 * played, and the catalogue rung or the next row is what remains.
 */
class BlockedStartRescueTest {

    private fun clock() = object {
        val startedAt = System.nanoTime() / 1_000_000L
        fun now() = System.nanoTime() / 1_000_000L
    }

    @Test
    fun `a resolver that answers after the deadline is discarded`() = runBlocking {
        val now = clock()
        val rescue = BlockedStartRescue(
            startedAtMs = now.startedAt,
            nowMs = now::now,
            totalBudgetMs = 100L,
            stepTimeoutMs = { _, _ -> 40L },
        )
        var finished = false
        val late = rescue.runStep(BlockedStartStep.PROXY) {
            // A blocking extraction ignores cancellation and returns late.
            withContext(NonCancellable) {
                delay(150L)
                finished = true
            }
            "late stream"
        }
        assertNull(late, "a stream handed back after the budget must not play")
        assertTrue(finished, "the fake resolver really did answer, just too late")
        assertTrue(rescue.isExpired() || rescue.elapsedMs() >= 40L)
    }

    @Test
    fun `the next rung is the one that answers inside the budget`() = runBlocking {
        val now = clock()
        val rescue = BlockedStartRescue(
            startedAtMs = now.startedAt,
            nowMs = now::now,
            totalBudgetMs = 500L,
            stepTimeoutMs = { step, _ ->
                when (step) {
                    BlockedStartStep.ALTERNATE -> 40L
                    BlockedStartStep.HIGH_QUALITY -> 100L
                    else -> 0L
                }
            },
        )
        val alternate = rescue.runStep(BlockedStartStep.ALTERNATE) {
            withContext(NonCancellable) { delay(120L) }
            "too late"
        }
        assertNull(alternate)
        val highQuality = rescue.runStep(BlockedStartStep.HIGH_QUALITY) {
            delay(1L)
            "flac"
        }
        assertEquals("flac", highQuality)
    }

    @Test
    fun `when no rung answers the outcome is the next row`() = runBlocking {
        val now = clock()
        val rescue = BlockedStartRescue(
            startedAtMs = now.startedAt,
            nowMs = now::now,
            totalBudgetMs = 100L,
            stepTimeoutMs = { _, _ -> 20L },
        )
        val alternate = rescue.runStep<Any>(BlockedStartStep.ALTERNATE) { null }
        val highQuality = rescue.runStep<Any>(BlockedStartStep.HIGH_QUALITY) { null }
        assertNull(alternate)
        assertNull(highQuality)
        assertEquals(
            BlockedStartStep.PROXY,
            BlockedStartPolicy.nextStep(BlockedStartStep.ALTERNATE),
        )
        assertEquals(
            BlockedStartStep.HIGH_QUALITY,
            BlockedStartPolicy.nextStep(BlockedStartStep.PROXY),
        )
        assertEquals(
            BlockedStartStep.NEXT_TRACK,
            BlockedStartPolicy.nextStep(BlockedStartStep.HIGH_QUALITY),
        )
    }

    @Test
    fun `first audio is measured from the play tap, not from a late transition`() {
        assertEquals(
            8_400L,
            BlockedStartPolicy.firstAudioMs(
                selectedAtMs = 1_000L,
                nowMs = 9_400L,
                alreadyAudible = false,
            ),
        )
        assertEquals(
            0L,
            BlockedStartPolicy.firstAudioMs(
                selectedAtMs = 1_000L,
                nowMs = 9_400L,
                alreadyAudible = true,
            ),
            "a crossfaded row really has been sounding",
        )
        assertNull(
            BlockedStartPolicy.firstAudioMs(
                selectedAtMs = null,
                nowMs = 9_400L,
                alreadyAudible = false,
            ),
            "a track that never sounded keeps firstAudio=-",
        )
        assertNotNull(
            BlockedStartPolicy.firstAudioMs(
                selectedAtMs = 2_000L,
                nowMs = 1_500L,
                alreadyAudible = false,
            ),
        )
    }
}
