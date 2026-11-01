package com.music.bitchord.ko

import android.content.Context
import android.widget.Toast
import com.music.bitchord.R
import com.music.bitchord.data.TrackDiagnostics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.player.LyricsTimingUi

/** Pins the currently playing moment to the diagnostic log. */
internal fun koReportProblem(
    context: Context,
    song: Song,
    positionMs: Long,
    lyrics: List<LyricLine>?,
    lyricsTiming: LyricsTimingUi,
) {
    val timed = lyrics.orEmpty()
    val index = timed.indexOfLast { it.timeMs > 0 && it.timeMs <= positionMs }
    val lineTimeMs = timed.getOrNull(index)?.timeMs ?: -1L
    val base = when {
        lyricsTiming.byAi -> "ai"
        lyricsTiming.shiftMs != null || lyricsTiming.rate != null -> "measured"
        else -> "none"
    }
    TrackDiagnostics.mark(
        song = song,
        positionMs = positionMs,
        lyricIndex = index,
        lyricTimeMs = lineTimeMs,
        sync = TrackDiagnostics.syncLabel(base, lyricsTiming.manualMs),
    )
    Toast.makeText(context, R.string.problem_marked, Toast.LENGTH_SHORT).show()
}
