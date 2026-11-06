package com.music.bitchord.playback.smart

import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsAiGuard
import com.music.bitchord.data.lyrics.LyricsMismatch
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.SongCheckWindow

/**
 * Which lyric sheet fits what the recording was heard singing.
 *
 * The sheet on screen is whichever provider's answer won a race of names: a
 * sheet that fits 100% could be replaced by a word-synced one at 69%
 * (rlYOsUfpi3M), and a download's stored sheet at 75% took over from one at 94%
 * (w58uXpyHWes). Words heard belong to the recording, so any sheet in hand can
 * be measured against them for free, by the fit that would time it.
 */
internal object SheetChoice {

    /** A sheet fitting this well stays: the race's pick may be word-synced where the other is not. */
    const val GOOD_ENOUGH_PERCENT = 85

    /** By how much another sheet must fit better. Two right sheets differ by about this (QYh6mYIJG2Y 86 and 82). */
    const val MARGIN_PERCENT = 10

    /** Fewer lines inside the words heard than this say too little to change a sheet for. */
    const val MIN_LINES = 8

    /** How much of [lines], heard as [heard], lies on one line: what a song check would call its match. */
    fun fitPercent(lines: List<LyricLine>, heard: List<LyricsWordAlign.Heard>): Int {
        if (heard.isEmpty()) return 0
        val sung = SongCheckWindow.indices(lines.map { it.timeMs }, heard.maxOf { it.endMs })
            .filter { lines[it].text.isNotBlank() }
        if (sung.size < MIN_LINES) return 0
        val texts = sung.map { lines[it].text }
        val starts = LyricsWordAlign.placeLines(texts, heard)
        val spellable = Deepgram.spellableLines(texts, Deepgram.languageFor(texts))
        return LyricsTiming.fitAi(lines, sung, starts, spellable)?.matchPercent ?: 0
    }

    /**
     * The candidate to put up instead of [current], or null to keep it. It must
     * clear the floor any timing has to clear, and beat [current] by
     * [MARGIN_PERCENT], and [current] must not already be good enough.
     */
    fun better(
        current: List<LyricLine>,
        candidates: List<LyricsRepository.Result>,
        heard: List<LyricsWordAlign.Heard>,
    ): LyricsRepository.Result? {
        if (heard.size < LyricsMismatch.MIN_HEARD_WORDS) return null
        val mine = fitPercent(current, heard)
        if (mine >= GOOD_ENOUGH_PERCENT) return null
        val (best, percent) = candidates.map { it to fitPercent(it.lines, heard) }.maxByOrNull { it.second } ?: return null
        return best.takeIf { percent >= maxOf(LyricsAiGuard.MIN_MATCH_PERCENT, mine + MARGIN_PERCENT) }
    }
}
