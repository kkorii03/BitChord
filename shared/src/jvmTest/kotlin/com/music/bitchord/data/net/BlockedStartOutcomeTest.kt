package com.music.bitchord.data.net

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The blocked-start rescue's end-to-end outcome with fake, deliberately slow
 * resolvers: a late answer is never played, a playable alternate/high-quality
 * copy wins inside the shared deadline, and with neither the next row is the
 * only outcome. Also covers the summary's firstAudio when a same-row refresh
 * follows the outgoing line being cleared.
 *
 * The resolver fakes stand in for the catalogue and high-quality lookups the
 * playback service runs, so the assertions exercise the same policy and clock
 * the service consumes rather than a private copy of the order.
 */
class BlockedStartOutcomeTest {

    private class Clock {
        val startedAt = System.nanoTime() / 1_000_000L
        fun now() = System.nanoTime() / 1_000_000L
    }

    /**
     * The service's rung walk, reduced to the two rungs that can make a sound:
     * alternate upload first, then a high-quality source. A null from both is
     * the next-row outcome.
     */
    private suspend fun chooseOutcome(
        rescue: BlockedStartRescue,
        alternate: suspend () -> String?,
        highQuality: suspend () -> String?,
    ): String {
        var alternateResult: String? = null
        var highQualityResult: String? = null
        for (rung in BlockedStartPolicy.plan(
            proxyAvailable = false,
            highQualityAvailable = true,
        )) {
            when (rung.step) {
                BlockedStartStep.ALTERNATE ->
                    alternateResult = rescue.runStep(BlockedStartStep.ALTERNATE) { alternate() }
                BlockedStartStep.HIGH_QUALITY ->
                    highQualityResult = rescue.runStep(BlockedStartStep.HIGH_QUALITY) { highQuality() }
                else -> Unit
            }
        }
        return highQualityResult ?: alternateResult ?: "next"
    }

    @Test
    fun `a slow alternate is dropped and the high-quality copy is played inside the budget`() =
        runBlocking {
            val clock = Clock()
            val rescue = BlockedStartRescue(
                startedAtMs = clock.startedAt,
                nowMs = clock::now,
                totalBudgetMs = 300L,
                stepTimeoutMs = { step, _ ->
                    when (step) {
                        BlockedStartStep.ALTERNATE -> 60L
                        BlockedStartStep.HIGH_QUALITY -> 120L
                        else -> 0L
                    }
                },
            )
            val outcome = chooseOutcome(
                rescue = rescue,
                alternate = { withContext(NonCancellable) { delay(120L) }; "late alternate" },
                highQuality = { delay(5L); "flac" },
            )
            assertEquals("flac", outcome, "the playable high-quality copy is (a)")
        }

    @Test
    fun `with no alternate and no high-quality copy the outcome is the next row`() = runBlocking {
        val clock = Clock()
        val rescue = BlockedStartRescue(
            startedAtMs = clock.startedAt,
            nowMs = clock::now,
            totalBudgetMs = 150L,
            stepTimeoutMs = { _, _ -> 40L },
        )
        val outcome = chooseOutcome(
            rescue = rescue,
            alternate = { withContext(NonCancellable) { delay(100L) }; "late alternate" },
            highQuality = { withContext(NonCancellable) { delay(100L) }; "late flac" },
        )
        assertEquals("next", outcome, "nothing inside the deadline means the next row is (b)")
    }

    @Test
    fun `a high-quality stream that arrives after the deadline is not played`() = runBlocking {
        val clock = Clock()
        val rescue = BlockedStartRescue(
            startedAtMs = clock.startedAt,
            nowMs = clock::now,
            totalBudgetMs = 80L,
            stepTimeoutMs = { _, _ -> 30L },
        )
        var resolverFinished = false
        val outcome = chooseOutcome(
            rescue = rescue,
            alternate = { null },
            highQuality = {
                withContext(NonCancellable) {
                    delay(100L)
                    resolverFinished = true
                }
                "late flac"
            },
        )
        assertEquals("next", outcome)
        assertEquals(true, resolverFinished, "the fake resolver really answered, just too late")
    }

    @Test
    fun `a same-row refresh keeps the first-audio measurement taken before the outgoing line`() {
        val previous = BlockedStartPolicy.firstAudioMs(
            selectedAtMs = 1_000L,
            nowMs = 9_400L,
            alreadyAudible = false,
        )
        assertEquals(8_400L, previous)

        // onTrackBecameCurrent clears the outgoing summary before starting the
        // next one; the captured value is what must survive a same-id refresh.
        assertEquals(
            8_400L,
            BlockedStartPolicy.summaryFirstAudioMs(
                previousVideoId = "e2T_MvOVapI",
                currentVideoId = "e2T_MvOVapI",
                previousFirstAudioMs = previous,
                alreadyAudible = true,
            ),
            "a same-row version/source refresh must not overwrite the wait with 0",
        )
        assertNull(
            BlockedStartPolicy.summaryFirstAudioMs(
                previousVideoId = "e2T_MvOVapI",
                currentVideoId = "e2T_MvOVapI",
                previousFirstAudioMs = previous,
                alreadyAudible = false,
                previousEnded = true,
            ),
            "a repeat of the same row is a new play, not a refresh",
        )
    }

    @Test
    fun `a different row or a row that never sounded does not inherit the old measurement`() {
        assertNull(
            BlockedStartPolicy.summaryFirstAudioMs(
                previousVideoId = "e2T_MvOVapI",
                currentVideoId = "jq9zy4TBygU",
                previousFirstAudioMs = 8_400L,
                alreadyAudible = false,
            ),
        )
        assertEquals(
            0L,
            BlockedStartPolicy.summaryFirstAudioMs(
                previousVideoId = "jq9zy4TBygU",
                currentVideoId = "jq9zy4TBygU",
                previousFirstAudioMs = null,
                alreadyAudible = true,
            ),
            "a crossfaded row really has been sounding",
        )
        assertNull(
            BlockedStartPolicy.summaryFirstAudioMs(
                previousVideoId = null,
                currentVideoId = "jq9zy4TBygU",
                previousFirstAudioMs = null,
                alreadyAudible = false,
            ),
            "a row that has not sounded has no figure yet",
        )
    }
}
