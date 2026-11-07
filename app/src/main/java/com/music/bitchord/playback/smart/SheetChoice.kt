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
     * [MARGIN_PERCENT], and [current] must not already be good enough (as measured now, or by
     * [knownFit]). Good enough but line-timed gives way to a sheet that also fits and times
     * every word: the first answer on screen is often the line-timed one, and a late word-timed
     * one now waits here for the words.
     *
     * @param knownFit what the app itself measured [current] at, when it has; the words in
     * hand can be a shorter listen than the one that measured it.
     */
    fun better(
        current: List<LyricLine>,
        candidates: List<LyricsRepository.Result>,
        heard: List<LyricsWordAlign.Heard>,
        knownFit: Int? = null,
    ): LyricsRepository.Result? {
        if (heard.size < LyricsMismatch.MIN_HEARD_WORDS) return null
        // 98% against 211 words, 72% against the 158 a later listen kept (w8P-BIQU1qI): not worse.
        val fitsNow = fitPercent(current, heard)
        val mine = maxOf(fitsNow, knownFit ?: 0)
        // Word times were set by someone listening, so a sheet that has them and clears the floor
        // gives way only to another that has them. Scored against such sheets, line-timed ones
        // that took over by fit alone were worse (QYh6mYIJG2Y: 83% against 93%, lrc.red replaced).
        val usable = if (current.any { it.hasStatedWords() } && fitsNow >= LyricsAiGuard.MIN_MATCH_PERCENT) {
            candidates.filter { sheet -> sheet.lines.any { it.hasStatedWords() } }
        } else {
            candidates
        }
        if (mine >= GOOD_ENOUGH_PERCENT) {
            if (current.any { it.hasStatedWords() }) return null
            return candidates.filter { sheet -> sheet.lines.any { it.hasStatedWords() } }
                .map { it to fitPercent(it.lines, heard) }
                .filter { it.second >= GOOD_ENOUGH_PERCENT }
                .maxByOrNull { it.second }?.first
        }
        val (best, percent) = usable.map { it to fitPercent(it.lines, heard) }.maxByOrNull { it.second } ?: return null
        return best.takeIf { percent >= maxOf(LyricsAiGuard.MIN_MATCH_PERCENT, mine + MARGIN_PERCENT) }
    }

    private fun LyricLine.hasStatedWords() = words.isNotEmpty() && !wordsEstimated
}
