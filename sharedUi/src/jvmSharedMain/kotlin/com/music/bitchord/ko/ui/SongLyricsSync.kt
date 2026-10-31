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
    val timing: LyricsTimingUi,
    val onAiTiming: (() -> Unit)?,
    val lines: List<LyricLine>,
    /** The lyrics clock now: the playhead with the all-songs offset applied, ms. */
    val lyricPositionMs: () -> Long,
    val onOffset: (Long) -> Unit,
)
