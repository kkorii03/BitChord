package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LyricLineIndexTest {

    private fun referenceRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
        val latest = lines.indexOfLast { it.timeMs <= positionMs }
        if (latest < 0) return emptyList()
        return (0..latest).filter { index ->
            val line = lines[index]
            index == latest || (!line.isGap &&
                (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
                line.timeMs <= positionMs && positionMs < line.endMs)
        }
    }

    private val overlapping = listOf(
        LyricLine(1_000, "upper", sungUntilMs = 4_000),
        LyricLine(2_000, "middle", sungUntilMs = 5_000),
        LyricLine(3_000, "lower", sungUntilMs = 6_000),
    )

    private val mixed = listOf(
        LyricLine(0, ""),
        LyricLine(1_000, "lead", sungUntilMs = 1_900,
            background = LyricLine(1_500, "echo", sungUntilMs = 3_000)),
        LyricLine(2_000, "next", sungUntilMs = 4_000),
        LyricLine(2_500, ""),
        LyricLine(4_200, "after"),
        LyricLine(4_200, "same stamp"),
        LyricLine(9_000, "late", sungUntilMs = 11_000),
    )

    @Test
    fun lineAtMatchesIndexOfLastOnSortedTimes() {
        val lines = listOf(
            LyricLine(0, "a"),
            LyricLine(1_000, "b"),
            LyricLine(1_000, "c"),
            LyricLine(2_500, "d"),
            LyricLine(9_999, "e"),
        )
        val index = LyricLineIndex(lines)
        for (position in listOf(-5L, 0L, 1L, 999L, 1_000L, 1_001L, 2_499L, 2_500L, 9_998L, 9_999L, 20_000L)) {
            val expected = lines.indexOfLast { it.timeMs <= position }
            assertEquals(expected, index.lineAt(position), "position $position")
        }
    }

    @Test
    fun activeRowsMatchesTheWholeListAnswer() {
        for (lines in listOf(overlapping, mixed, overlapping.map { it.copy(sungUntilMs = null) })) {
            val index = LyricLineIndex(lines)
            var position = -1_000L
            while (position <= 12_500L) {
                assertEquals(referenceRows(lines, position), index.activeRows(position), "position $position")
                position += 100L
            }
        }
    }

    private val leadWithEcho = listOf(
        LyricLine(1_000, "lead", sungUntilMs = 1_900,
            background = LyricLine(1_500, "echo", sungUntilMs = 3_000)),
        LyricLine(2_000, "next", sungUntilMs = 4_000),
    )

    @Test
    fun backgroundVocalKeepsItsParentRowAnchored() {
        val index = LyricLineIndex(leadWithEcho)
        assertEquals(listOf(0, 1), index.activeRows(2_500))
        assertEquals(listOf(1), index.activeRows(3_000))
    }

    @Test
    fun unsortedTimesKeepTheOldAnswer() {
        val lines = listOf(
            LyricLine(2_000, "b", sungUntilMs = 5_000),
            LyricLine(1_000, "a", sungUntilMs = 4_000),
            LyricLine(3_000, "c"),
        )
        val index = LyricLineIndex(lines)
        for (position in listOf(-5L, 0L, 1_000L, 2_500L, 4_500L, 6_000L)) {
            assertEquals(lines.indexOfLast { it.timeMs <= position }, index.lineAt(position))
            assertEquals(referenceRows(lines, position), index.activeRows(position), "position $position")
        }
    }

    @Test
    fun emptyListHasNoLineAndNoRows() {
        val index = LyricLineIndex(emptyList())
        assertEquals(-1, index.lineAt(1_000))
        assertEquals(emptyList<Int>(), index.activeRows(1_000))
    }

    @Test
    fun unchangedRowsAreReusedRatherThanRebuilt() {
        val index = LyricLineIndex(overlapping)
        val first = index.activeRows(1_100)
        val same = index.activeRows(1_900)
        assertTrue(first == same)
        assertSame(first, same)
        index.activeRows(3_500)
        val back = index.activeRows(1_200)
        assertEquals(first, back)
        assertSame(back, index.activeRows(1_800))
    }
}
