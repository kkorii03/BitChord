package com.music.bitchord.ko

import com.music.bitchord.playback.smart.AnalysisWindow

/**
 * [KO] UPG-6: which automix window passes count as work done ahead of time at
 * the decode gate ([com.music.bitchord.playback.smart.DecodePriorityGate]).
 *
 * The opening (HEAD) is only ever asked for the next row
 * ([com.music.bitchord.playback.smart.AutomixWorkPolicy.nextAnalysisTarget]),
 * a track that is not playing yet. Its decode sat in the same line as the
 * current track's HQ verification, so a lossless swap for the song the listener
 * hears waited behind the next song's analysis. The current track's TAIL keeps
 * its place: the handoff is built from it.
 */
object KoAnalysisPriority {
    fun aheadOfTime(window: AnalysisWindow): Boolean = window == AnalysisWindow.HEAD
}
