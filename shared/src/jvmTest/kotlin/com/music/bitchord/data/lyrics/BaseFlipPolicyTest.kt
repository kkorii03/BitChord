package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One base per evidence, not one per caller (11-08 phone log).
 *
 * 1WZ1tfpyu3M: the same 69 heard lines scored the AI answer 68 ms / 96% and the
 * prepared measurement -200 ms at 418 ms / 17%. The screen and the song check
 * picked the AI answer, then a caller that lacked the AI answer for its text key
 * re-picked the measurement, then a caller that had heard nothing re-picked it
 * from the fixed order: the base flipped three times in 350 ms and kept the
 * 418 ms measurement.
 *
 * kCHNNCT-blQ: a sheet change left a caller with no heard words, whose fixed
 * order moved the base 5405 → 5496 ms after the heard words had settled it.
 */
class BaseFlipPolicyTest {

    /** The decision kept after [calls] arrive in this order, as `LyricsTiming.baseOf` settles them. */
    private fun settle(calls: List<BaseChoicePolicy.Evidence>): BaseChoicePolicy.Evidence? =
        calls.fold<BaseChoicePolicy.Evidence, BaseChoicePolicy.Evidence?>(null) { kept, next ->
            if (BaseChoicePolicy.replaces(kept, next)) next else kept
        }

    private fun evidence(name: String, matched: Int, within: Double, median: Long) =
        BaseChoicePolicy.Evidence(name, matched, within, median)

    /** 1WZ1tfpyu3M, 07:57:32 → 08:00:23, the decision each caller reached. */
    private val oneWzOne = listOf(
        evidence(BaseChoicePolicy.AI, 27, 0.85, 43),
        evidence(BaseChoicePolicy.NONE, 27, 0.0, 2_414),
        evidence(BaseChoicePolicy.MEASURED, 27, 0.0, 9_958),
        evidence(BaseChoicePolicy.MEASURED, 27, 0.85, 43),
        evidence(BaseChoicePolicy.MEASURED, 53, 0.77, 81),
        evidence(BaseChoicePolicy.AI, 69, 0.96, 68),
        evidence(BaseChoicePolicy.MEASURED, 69, 0.17, 418),
        evidence(BaseChoicePolicy.MEASURED, 0, 0.0, 0),
    )

    @Test
    fun theAgreeingBaseEndsTheCallersOfOneWzOne() {
        assertEquals(BaseChoicePolicy.AI, settle(oneWzOne)?.name)
        // Counted lines are the evidence: the later 69-line measurement at
        // 418 ms / 17% must not undo the 69-line AI answer at 68 ms / 96%.
        assertEquals(
            BaseChoicePolicy.AI,
            settle(
                listOf(
                    evidence(BaseChoicePolicy.AI, 69, 0.96, 68),
                    evidence(BaseChoicePolicy.MEASURED, 69, 0.17, 418),
                ),
            )?.name,
        )
    }

    @Test
    fun theSameEvidenceInAnyArrivalOrderKeepsTheSameBase() {
        for (order in permutations(oneWzOne)) {
            assertEquals(BaseChoicePolicy.AI, settle(order)?.name, "order: $order")
        }
    }

    @Test
    fun repeatedEvidenceNeverMovesTheBase() {
        val kept = evidence(BaseChoicePolicy.AI, 69, 0.96, 68)
        assertFalse(BaseChoicePolicy.replaces(kept, kept))
        // A caller that heard nothing can never move a decision the words made.
        assertFalse(BaseChoicePolicy.replaces(kept, evidence(BaseChoicePolicy.MEASURED, 0, 0.0, 0)))
        // A caller with less of the same evidence cannot either.
        assertFalse(BaseChoicePolicy.replaces(kept, evidence(BaseChoicePolicy.MEASURED, 53, 0.77, 81)))
        // The first decision is always taken, and more heard lines always win.
        assertTrue(BaseChoicePolicy.replaces(null, kept))
        assertTrue(BaseChoicePolicy.replaces(kept, evidence(BaseChoicePolicy.MEASURED, 70, 0.10, 900)))
    }

    @Test
    fun aCallerWithNoHeardWordsKeepsTheSettledBase() {
        // kCHNNCT-blQ: 59 heard lines settled the measurement at 5405 ms, then a
        // sheet change left a caller with no words for that key; its fixed order
        // wanted 5496 ms.
        val settled = evidence(BaseChoicePolicy.MEASURED, 59, 0.56, 215)
        val fixedOrder = evidence(BaseChoicePolicy.MEASURED, 0, 0.0, 0)
        val kept = settle(listOf(settled, fixedOrder))
        assertSame(settled, kept)
        assertFalse(BaseChoicePolicy.replaces(settled, fixedOrder))
    }

    private fun permutations(
        calls: List<BaseChoicePolicy.Evidence>,
    ): List<List<BaseChoicePolicy.Evidence>> = when {
        calls.size <= 1 -> listOf(calls)
        else -> calls.indices.flatMap { index ->
            val head = calls[index]
            permutations(calls.filterIndexed { at, _ -> at != index }).map { listOf(head) + it }
        }
    }
}
