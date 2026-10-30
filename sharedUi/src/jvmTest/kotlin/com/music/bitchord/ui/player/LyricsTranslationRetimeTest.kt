package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a translated sheet has to be made again after its timings change.
 *
 * The line start alone is not enough: word sync can arrive later on the same
 * line stamps, and then the translation's sweep would keep projecting the old
 * envelope unless it is rebuilt.
 */
class LyricsTranslationRetimeTest {

    private fun original(
        timeMs: Long = 1_000,
        sungUntilMs: Long? = null,
        words: List<LyricWord> = emptyList(),
        background: LyricLine? = null,
    ) = LyricLine(timeMs = timeMs, text = "첫 줄", sungUntilMs = sungUntilMs, words = words, background = background)

    @Test
    fun lineTimingsAloneStillMatch() {
        val source = original()
        assertTrue(sameTiming(listOf(source), listOf(source)))
    }

    @Test
    fun arrivingWordSyncAsksForARebuild() {
        val before = original()
        val after = original(
            words = listOf(
                LyricWord(1_000, 1_400, "첫"),
                LyricWord(1_400, 1_800, "줄"),
            ),
        )
        assertFalse(sameTiming(listOf(before), listOf(after)))
    }

    @Test
    fun translatedLineCarriesTheOldSourceWordsForComparison() {
        val source = original(
            words = listOf(
                LyricWord(1_000, 1_400, "첫"),
                LyricWord(1_400, 1_800, "줄"),
            ),
        )
        val translated = source.copy(
            text = "first line",
            words = listOf(LyricWord(1_000, 1_800, "first line")),
            timingSource = source,
        )
        assertTrue(sameTiming(listOf(translated), listOf(source)))

        val moved = source.copy(
            words = listOf(
                LyricWord(1_000, 1_300, "첫"),
                LyricWord(1_300, 1_900, "줄"),
            ),
        )
        assertFalse(sameTiming(listOf(translated), listOf(moved)))
    }

    @Test
    fun aChangedHoldOrBackgroundAlsoAsksForARebuild() {
        val source = original(sungUntilMs = 2_000)
        assertFalse(sameTiming(listOf(source), listOf(source.copy(sungUntilMs = 2_500))))

        val withBacking = source.copy(background = original(timeMs = 1_100))
        val movedBacking = source.copy(background = original(timeMs = 1_250))
        assertFalse(sameTiming(listOf(withBacking), listOf(movedBacking)))
    }
}
