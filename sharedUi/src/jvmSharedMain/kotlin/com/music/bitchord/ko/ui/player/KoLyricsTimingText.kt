package com.music.bitchord.ko.ui.player

import androidx.compose.runtime.Composable
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.player.LyricsTimingUi
import org.jetbrains.compose.resources.stringResource

/**
 * What was done to the timing, with its figures — "자동 보정 +3.2초 · 속도
 * 0.98배", "AI 타이밍 +2.1초 · 일치 85%" — or null when nothing moved the lines.
 */
@Composable
internal fun koLyricsTimingDetail(timing: LyricsTimingUi): String? {
    if (timing.shiftMs == null && !timing.byAi) return null
    val details = buildList {
        timing.shiftMs?.let { add(stringResource(Res.string.lyrics_timing_seconds, koSignedSeconds(it))) }
        timing.rate?.let { add(stringResource(Res.string.lyrics_timing_rate, String.format(java.util.Locale.ROOT, "%.2f", it))) }
        if (timing.byAi) timing.matchPercent?.let { add(stringResource(Res.string.lyrics_timing_match, it.toString())) }
        if (timing.byAi) timing.aiEngine?.let { add(it) }
    }.joinToString(" · ")
    return stringResource(
        if (timing.byAi) Res.string.lyrics_timing_detail_ai else Res.string.lyrics_timing_detail_auto,
        details,
    ).trim()
}

internal fun koSignedSeconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%+.1f", ms / 1000.0)
