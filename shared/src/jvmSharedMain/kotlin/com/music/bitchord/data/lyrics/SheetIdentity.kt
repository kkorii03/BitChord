package com.music.bitchord.data.lyrics

/**
 * Which lyrics sheet a settled base belongs to.
 *
 * The screen and the next-song bundle ask for the base with whatever sheet is
 * on show, and a fresh lookup can replace that sheet under the same song. The
 * base the old sheet's words settled must not move the new sheet's lines: it is
 * another arrangement's answer (11-08 1WZ1tfpyu3M: `used=KUGOU` replaced by
 * `used=QQ_MUSIC`, and the new sheet took the old `measured` base by fixed
 * order).
 *
 * The same sheet still reaches callers in different forms: line-stamped or
 * word-stamped, with or without the blank gap lines a provider writes, with
 * stray whitespace. Those formed different text keys and let one form settle a
 * decision the other never saw, so only the words are read here.
 *
 * A romanized or translated copy is different [LyricLine.text] and so a
 * different sheet; callers time the provider's own words, the display
 * transforms come after timing.
 */
object SheetIdentity {

    private val WHITESPACE = Regex("\\s+")

    /**
     * The sheet's words, in order, with blank lines and whitespace runs dropped,
     * as a canonical [String].
     *
     * Each line is length-prefixed so the encoding is injective: no two different
     * sheets (different words, or the same words split into different lines) can
     * produce the same identity. An `Int` hash could collide and let one sheet
     * share another sheet's decision, which is exactly the fresh-start guarantee
     * this identity exists for.
     */
    fun of(lines: List<LyricLine>): String = buildString {
        for (text in lines.asSequence()
            .map { it.text.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
        ) {
            append(text.length).append(':').append(text)
        }
    }
}
