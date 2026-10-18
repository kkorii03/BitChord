package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricLineIndex

/**
 * Keep every unfinished vocal visible, including overlaps spanning more than two rows.
 *
 * One-shot answer for callers with no index at hand; the panel keeps a
 * [LyricLineIndex] across frames so the clock ticks do not rescan the list.
 */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> =
    LyricLineIndex(lines).activeRows(positionMs)
