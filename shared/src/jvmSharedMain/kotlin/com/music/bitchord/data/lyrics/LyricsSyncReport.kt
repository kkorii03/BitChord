package com.music.bitchord.data.lyrics

import java.util.Locale
import kotlin.math.abs

/**
 * A listener's report that the line on screen is not the line being sung.
 *
 * Pure: the log line and the toast's figure are the same arithmetic wherever
 * they are written, and neither needs a player to be tested.
 */
object LyricsSyncReport {

    /**
     * `sync-report: <videoId> line=<n>/<total> shown=<ms> tapped=<ms>
     * error=<shown-tapped> source=.. base=.. shift=..ms manual=..ms playing=..`
     *
     * `line` is one-based, as the toast says it. `shown` and `tapped` are on
     * the same clock — the lyric clock the panel draws from — so a positive
     * error is the lyrics running late.
     */
    fun line(
        videoId: String,
        line: Int,
        total: Int,
        shownMs: Long,
        tappedMs: Long,
        source: String,
        base: String,
        shiftMs: Long?,
        manualMs: Long,
        playing: String,
    ): String = buildString {
        append("sync-report: ").append(videoId)
        append(" line=").append(line).append('/').append(total)
        append(" shown=").append(shownMs)
        append(" tapped=").append(tappedMs)
        append(" error=").append(shownMs - tappedMs)
        append(" source=").append(source.ifBlank { "none" })
        append(" base=").append(base.ifBlank { "none" })
        append(" shift=").append(shiftMs ?: "-").append("ms")
        append(" manual=").append(manualMs).append("ms")
        append(" playing=").append(playing.ifBlank { videoId })
    }

    /** The error as the toast writes it: one decimal, in seconds. */
    fun seconds(errorMs: Long): String =
        String.format(Locale.ROOT, "%.1f", abs(errorMs) / 1000.0)

    /** A positive error is the line shown after it was sung, so the lyrics are late. */
    fun isLate(errorMs: Long): Boolean = errorMs >= 0
}
