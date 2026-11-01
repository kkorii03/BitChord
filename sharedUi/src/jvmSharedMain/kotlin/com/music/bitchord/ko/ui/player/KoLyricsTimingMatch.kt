package com.music.bitchord.ko.ui.player

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord

/** The same small lines re-timed: same count, same words. */
internal fun koSameWords(a: List<LyricLine>?, b: List<LyricLine>?): Boolean =
    a != null && b != null && a.size == b.size && a.indices.all { a[it].text == b[it].text }

/**
 * Whether the translated lines were made from the same timing as [source].
 *
 * Line starts alone are not enough. Word sync often arrives after the first
 * line-only sheet, on exactly the same stamps, and a hold can change without
 * its start moving; either one leaves the translated sheet projecting the old
 * envelope unless it is rebuilt. A translated line keeps the original it was
 * made from in [LyricLine.timingSource], which is what makes the comparison
 * possible without confusing its own translated words with the source's.
 */
internal fun koSameTiming(made: List<LyricLine>, source: List<LyricLine>): Boolean =
    made.size == source.size && made.indices.all { index ->
        val a = made[index]
        val b = source[index]
        a.timeMs == b.timeMs &&
            a.sungUntilMs == b.sungUntilMs &&
            sourceWordsOf(a) == sourceWordsOf(b) &&
            sameBackgroundTiming(a.background, b.background)
    }

/** The source words a display line was timed from; its own words when untranslated. */
private fun sourceWordsOf(line: LyricLine): List<LyricWord> =
    line.timingSource?.words ?: line.words

private fun sameBackgroundTiming(a: LyricLine?, b: LyricLine?): Boolean {
    if (a == null || b == null) return a == null && b == null
    return a.timeMs == b.timeMs &&
        a.sungUntilMs == b.sungUntilMs &&
        sourceWordsOf(a) == sourceWordsOf(b)
}
