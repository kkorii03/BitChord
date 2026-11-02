package com.music.bitchord.ko.ui

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.LyricsTimingUi

/**
 * What the sync drawer needs to correct the song playing on its own: its
 * correction now, the lines as they are showing (that correction included),
 * where the lyrics clock is, and where to send a new correction.
 */
internal class SongLyricsSync(
    val offsetMs: Long,
    /** How the lines were moved onto the video's clock, for the figures shown here. */
    val timing: LyricsTimingUi,
    /** Asks Gemini to time the lines (or takes its answer back); null where it can't. */
    val onAiTiming: (() -> Unit)?,
    val lines: List<LyricLine>,
    /** The lyrics clock now: the playhead with the all-songs offset applied, ms. */
    val lyricPositionMs: () -> Long,
    val onOffset: (Long) -> Unit,
)
