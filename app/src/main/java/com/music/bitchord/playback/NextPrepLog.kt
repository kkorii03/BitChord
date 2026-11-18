package com.music.bitchord.playback

import com.music.bitchord.data.LyricsFacts
import com.music.bitchord.data.TrackDiagnostics
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.smart.LyricsTiming
import java.util.Locale

/**
 * The words the next-song bundle writes for its lyrics and sync steps.
 *
 * "none" has to mean "asked and found nothing". A step the safety switches or
 * the settings kept from running is "off", not a miss: reporting both as
 * "none" made the field logs read as a lyrics failure rate when a large part
 * of it was a deliberately disabled path.
 */
internal object NextPrepLog {

    fun lyrics(source: LyricsSource?, attempted: Boolean): String = when {
        source != null -> source.name.lowercase(Locale.ROOT)
        attempted -> "none"
        else -> "off"
    }

    fun sync(applied: Boolean, attempted: Boolean): String = when {
        applied -> "applied"
        attempted -> "skipped"
        else -> "off"
    }

    /**
     * The per-track summary's lyrics fact for a row this bundle prepared, in
     * the lyrics screen's own words ([LyricsSource.label], [TrackDiagnostics.syncLabel]),
     * so a track played with no lyrics screen still reports what was found
     * (LOG-1117 G1). Null when the bundle found no lyrics.
     */
    fun summaryFacts(
        source: LyricsSource?,
        hasLines: Boolean,
        timing: LyricsTiming.Timing?,
    ): LyricsFacts? {
        if (!hasLines) return null
        val base = timing?.base
        val baseLabel = when {
            base == null -> "none"
            base.byAi -> "ai"
            else -> "measured"
        }
        val manualMs = timing?.manualMs ?: 0L
        return LyricsFacts(
            provider = source?.label ?: "file",
            sync = TrackDiagnostics.syncLabel(baseLabel, manualMs, refined = timing != null && (timing.refineMs != 0L || timing.refineSlopePerMin != 0.0)), // [KO] log1819
            shiftMs = null,
            matchPercent = (base as? LyricsTiming.Adjustment.Mapped)?.matchPercent,
            manualOffsetMs = manualMs,
        )
    }
}
