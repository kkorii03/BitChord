package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The blocked-start rescue is a fixed order with a shared deadline. These are
 * the decisions the resolver and the playback service consume instead of each
 * holding their own private timeout, so a change to one cannot silently make
 * the other spend the listener's silence twice.
 */
class BlockedStartPolicyTest {

    @Test
    fun `the rescue asks for another copy, then the proxy, then a high-quality source, then the next row`() {
        val plan = BlockedStartPolicy.plan(proxyAvailable = true, highQualityAvailable = true)
        assertEquals(
            listOf(
                BlockedStartStep.ALTERNATE,
                BlockedStartStep.PROXY,
                BlockedStartStep.HIGH_QUALITY,
                BlockedStartStep.NEXT_TRACK,
            ),
            plan.map { it.step },
        )
        assertEquals(BlockedStartStep.PROXY, BlockedStartPolicy.nextStep(BlockedStartStep.ALTERNATE))
        assertEquals(BlockedStartStep.HIGH_QUALITY, BlockedStartPolicy.nextStep(BlockedStartStep.PROXY))
        assertEquals(BlockedStartStep.NEXT_TRACK, BlockedStartPolicy.nextStep(BlockedStartStep.HIGH_QUALITY))
        assertNull(BlockedStartPolicy.nextStep(BlockedStartStep.NEXT_TRACK))
    }

    @Test
    fun `steps that cannot run are left out but the order never changes`() {
        val plan = BlockedStartPolicy.plan(proxyAvailable = false, highQualityAvailable = true)
        assertEquals(
            listOf(BlockedStartStep.ALTERNATE, BlockedStartStep.HIGH_QUALITY, BlockedStartStep.NEXT_TRACK),
            plan.map { it.step },
        )
        assertFalse(plan.any { it.step == BlockedStartStep.PROXY })
    }

    @Test
    fun `every step is bounded by the whole rescue's nine seconds`() {
        val plan = BlockedStartPolicy.plan(proxyAvailable = true, highQualityAvailable = true)
        val spent = plan.filter { it.step != BlockedStartStep.NEXT_TRACK }.sumOf { it.timeoutMs }
        assertTrue(plan.all { it.timeoutMs <= BlockedStartPolicy.TOTAL_BUDGET_MS })
        assertTrue(
            spent <= BlockedStartPolicy.TOTAL_BUDGET_MS,
            "the rescue may not promise more than the listener's nine seconds: $spent",
        )
        assertTrue(
            BlockedStartPolicy.DIRECT_HOLD_MS + spent <= BlockedStartPolicy.TOTAL_BUDGET_MS,
            "the direct hold is spent from the same nine seconds: " +
                "${BlockedStartPolicy.DIRECT_HOLD_MS + spent}",
        )
        assertTrue(
            BlockedStartPolicy.RESOLVER_BUDGET_MS + BlockedStartPolicy.HIGH_QUALITY_TIMEOUT_MS <=
                BlockedStartPolicy.TOTAL_BUDGET_MS,
            "the resolver must leave the high-quality window inside the same nine seconds",
        )
    }

    @Test
    fun `the high-quality search only runs when the alternate cannot`() {
        assertTrue(
            BlockedStartPolicy.shouldSearchHighQuality(
                alternateFound = false,
                allowAlternateRecording = true,
                networkFault = false,
                definitelyGone = false,
            ),
        )
        assertFalse(
            BlockedStartPolicy.shouldSearchHighQuality(
                alternateFound = true,
                allowAlternateRecording = true,
                networkFault = false,
                definitelyGone = false,
            ),
            "an alternate plays first",
        )
        assertFalse(
            BlockedStartPolicy.shouldSearchHighQuality(
                alternateFound = false,
                allowAlternateRecording = false,
                networkFault = false,
                definitelyGone = false,
            ),
            "a mid-song failure must not become another recording",
        )
        assertFalse(
            BlockedStartPolicy.shouldSearchHighQuality(
                alternateFound = false,
                allowAlternateRecording = true,
                networkFault = true,
                definitelyGone = false,
            ),
            "a network fault is retried, not substituted",
        )
        assertFalse(
            BlockedStartPolicy.shouldSearchHighQuality(
                alternateFound = false,
                allowAlternateRecording = true,
                networkFault = false,
                definitelyGone = true,
            ),
            "a truly gone upload is skipped",
        )
    }

    @Test
    fun `a step never gets more time than is left`() {
        assertEquals(
            BlockedStartPolicy.HIGH_QUALITY_TIMEOUT_MS,
            BlockedStartPolicy.timeoutFor(BlockedStartStep.HIGH_QUALITY, 0L),
        )
        assertEquals(0L, BlockedStartPolicy.timeoutFor(BlockedStartStep.ALTERNATE, 9_000L))
        assertEquals(0L, BlockedStartPolicy.timeoutFor(BlockedStartStep.PROXY, 25_000L))
    }

    @Test
    fun `a direct refusal starts the proxy only after a short hold, never before`() {
        assertFalse(
            BlockedStartPolicy.shouldStartProxyEarly(
                directRefused = true,
                innerTubeXStillRunning = true,
                elapsedMs = BlockedStartPolicy.DIRECT_HOLD_MS - 1,
            ),
        )
        assertTrue(
            BlockedStartPolicy.shouldStartProxyEarly(
                directRefused = true,
                innerTubeXStillRunning = true,
                elapsedMs = BlockedStartPolicy.DIRECT_HOLD_MS,
            ),
        )
        assertFalse(
            BlockedStartPolicy.shouldStartProxyEarly(
                directRefused = false,
                innerTubeXStillRunning = true,
                elapsedMs = 5_000L,
            ),
            "nothing was refused, so there is nothing to escalate",
        )
        assertFalse(
            BlockedStartPolicy.shouldStartProxyEarly(
                directRefused = true,
                innerTubeXStillRunning = false,
                elapsedMs = 5_000L,
            ),
            "InnerTubeX already answered; its answer is in hand",
        )
    }

    @Test
    fun `the plan is the same walk nextStep describes`() {
        val walked = buildList {
            var step: BlockedStartStep? = BlockedStartStep.DIRECT
            while (true) {
                step = BlockedStartPolicy.nextStep(step!!) ?: break
                add(step)
            }
        }
        assertEquals(walked, BlockedStartPolicy.plan(true, true).map { it.step })
        assertEquals(
            listOf(BlockedStartStep.ALTERNATE, BlockedStartStep.NEXT_TRACK),
            BlockedStartPolicy.plan(proxyAvailable = false, highQualityAvailable = false)
                .map { it.step },
        )
    }

    @Test
    fun `a late rescue still gets its high-quality window inside the budget`() {
        // 10-31 Betty: the resolver had spent about five seconds before the
        // catalogue rungs ran. The greedy split gave the alternate the last
        // second and the high-quality search none, so the track that a Qobuz
        // FLAC could play was skipped instead. The resolver is now bounded to
        // its own five seconds, leaving that window intact.
        assertEquals(
            0L,
            BlockedStartPolicy.timeoutFor(BlockedStartStep.ALTERNATE, 5_000L),
            "the alternate leaves the high-quality window free",
        )
        assertEquals(
            BlockedStartPolicy.HIGH_QUALITY_TIMEOUT_MS,
            BlockedStartPolicy.timeoutFor(BlockedStartStep.HIGH_QUALITY, 5_000L),
        )
        assertEquals(
            0L,
            BlockedStartPolicy.timeoutFor(BlockedStartStep.HIGH_QUALITY, 9_000L),
            "a spent budget does not start a four-second search past the ceiling",
        )
        assertEquals(
            BlockedStartPolicy.ALTERNATE_TIMEOUT_MS,
            BlockedStartPolicy.timeoutFor(BlockedStartStep.ALTERNATE, 2_000L),
            "early on the alternate gets its full window",
        )
    }

    @Test
    fun `extraction is skipped only after both direct and proxy refused`() {
        assertTrue(BlockedStartPolicy.shouldSkipExtraction(directRefused = true, proxyFailed = true))
        assertFalse(BlockedStartPolicy.shouldSkipExtraction(directRefused = true, proxyFailed = false))
        assertFalse(BlockedStartPolicy.shouldSkipExtraction(directRefused = false, proxyFailed = true))
    }

    @Test
    fun `the step log line names the id, step, result and elapsed time`() {
        assertEquals(
            "blocked-start: abc step=proxy result=failed ms=3700",
            BlockedStartPolicy.logLine("abc", BlockedStartStep.PROXY, "failed", 3_700L),
        )
    }
}
