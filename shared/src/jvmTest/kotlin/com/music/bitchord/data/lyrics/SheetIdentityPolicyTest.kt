package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * Which sheet a settled base belongs to (11-08 phone log).
 *
 * 1WZ1tfpyu3M: the provider's sheet was replaced under the song (`used=KUGOU`
 * → `used=QQ_MUSIC`, then the caller with the new sheet took the old sheet's
 * `measured` base by fixed order). The new sheet must start fresh, never
 * inherit the base the old bytes settled.
 *
 * The same sheet reaches the screen and the next-song bundle in different
 * forms — word-stamped or line-stamped, with a provider's blank gap lines,
 * with stray whitespace — which gave different text keys and let one form
 * settle a decision the other form never saw. The identity is the sheet's
 * words, so every form settles one decision.
 *
 * Tokens only; no lyric text.
 */
class SheetIdentityPolicyTest {

    private fun sheet(vararg texts: String) = texts.map { LyricLine(it.hashCode().toLong() * 1_000L, it) }

    private fun wordTimed(line: LyricLine) = line.copy(
        words = listOf(LyricWord(line.timeMs, line.timeMs + 500, line.text)),
    )

    private fun evidence(name: String, matched: Int, within: Double, median: Long) =
        BaseChoicePolicy.Evidence(name, matched, within, median)

    /** `LyricsTiming.settleBase`, on the same rule, keyed by [SheetIdentity]. */
    private fun settle(
        kept: MutableMap<String, BaseChoicePolicy.Evidence>,
        lines: List<LyricLine>,
        next: BaseChoicePolicy.Evidence,
    ): BaseChoicePolicy.Evidence {
        val key = SheetIdentity.of(lines)
        val previous = kept[key]
        val winner = if (previous == null || BaseChoicePolicy.replaces(previous, next)) next else previous
        kept[key] = winner
        return winner
    }

    @Test
    fun aReplacedSheetDoesNotInheritTheOldSheetsBase() {
        val decisions = mutableMapOf<String, BaseChoicePolicy.Evidence>()
        val oldSheet = sheet("t01", "t02")
        settle(decisions, oldSheet, evidence(BaseChoicePolicy.AI, 69, 0.96, 68))
        // A caller whose sheet was just replaced hears nothing for the new
        // sheet: its fixed-order pick is a fresh decision, not the old one.
        val fresh = settle(decisions, sheet("t03", "t04"), evidence(BaseChoicePolicy.MEASURED, 0, 0.0, 0))
        assertEquals(BaseChoicePolicy.MEASURED, fresh.name)
        assertEquals(2, decisions.size)
    }

    @Test
    fun theSameSheetInAnotherFormKeepsTheSettledDecision() {
        val decisions = mutableMapOf<String, BaseChoicePolicy.Evidence>()
        val lineTimed = sheet("t01", "t02")
        val settled = settle(decisions, lineTimed, evidence(BaseChoicePolicy.AI, 69, 0.96, 68))
        val otherForm = buildList {
            add(LyricLine(0L, ""))
            add(wordTimed(lineTimed[0].copy(text = "  ${lineTimed[0].text} ")))
            add(wordTimed(lineTimed[1]))
        }
        val kept = settle(decisions, otherForm, evidence(BaseChoicePolicy.MEASURED, 69, 0.17, 418))
        assertSame(settled, kept)
        assertEquals(1, decisions.size)
    }

    @Test
    fun identityIgnoresFormNotWords() {
        val base = sheet("t01", "t02")
        val identity = SheetIdentity.of(base)
        // Word-stamped copy of the same sheet.
        assertEquals(identity, SheetIdentity.of(base.map { wordTimed(it) }))
        // A provider's blank gap line and stray whitespace.
        assertEquals(
            identity,
            SheetIdentity.of(listOf(LyricLine(0L, "")) + base.map { it.copy(text = " ${it.text}\t") }),
        )
        // Different words are a different sheet.
        assertNotEquals(identity, SheetIdentity.of(sheet("t01", "t03")))
        // Nothing timed is one identity, however it is spelled.
        assertEquals(SheetIdentity.of(emptyList()), SheetIdentity.of(listOf(LyricLine(0L, "  "))))
    }

    @Test
    fun differentLineSplitsAreDifferentSheets() {
        // Length-prefixed lines: "t01" split one way cannot alias another split.
        assertNotEquals(SheetIdentity.of(sheet("t01")), SheetIdentity.of(sheet("t0", "1")))
    }
}
