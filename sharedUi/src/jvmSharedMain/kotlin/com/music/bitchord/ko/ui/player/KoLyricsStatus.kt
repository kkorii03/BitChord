package com.music.bitchord.ko.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.player.LyricsTimingUi
import com.music.bitchord.ui.player.LyricsTranslationUiState
import org.jetbrains.compose.resources.stringResource

/**
 * The label beside the translate button: one short noun label, never a sentence
 * and never joined with anything else, so it has room to show in full. What the
 * translation and the timing are doing lives in the sheet its chevron opens.
 *
 * It takes no translation state on purpose. "Already Korean", "translated" and
 * "translating" repeat what the words and the translate button already show.
 */
internal enum class KoLyricsBarStatus { Source, Looking, NoLyrics, Saved }

internal fun koLyricsBarStatus(
    hasSource: Boolean,
    unavailable: Boolean,
    hasLyrics: Boolean,
): KoLyricsBarStatus = when {
    hasSource -> KoLyricsBarStatus.Source
    unavailable -> KoLyricsBarStatus.NoLyrics
    !hasLyrics -> KoLyricsBarStatus.Looking
    else -> KoLyricsBarStatus.Saved
}

@Composable
internal fun koLyricsBarText(
    source: LyricsSource?,
    unavailable: Boolean,
    hasLyrics: Boolean,
): String = when (koLyricsBarStatus(source != null, unavailable, hasLyrics)) {
    KoLyricsBarStatus.Source -> stringResource(Res.string.lyrics_by, source?.label.orEmpty())
    KoLyricsBarStatus.Looking -> stringResource(Res.string.lyrics_looking)
    KoLyricsBarStatus.NoLyrics -> stringResource(Res.string.lyrics_not_available)
    KoLyricsBarStatus.Saved -> stringResource(Res.string.lyrics_saved_with_download)
}

/** What the sheet says about the translation; null when it has nothing to add. */
internal enum class KoTranslationDetail { Translating, ByGemini, Translated }

internal fun koTranslationDetail(
    state: LyricsTranslationUiState,
    showing: Boolean,
): KoTranslationDetail? = when {
    state is LyricsTranslationUiState.Loading -> KoTranslationDetail.Translating
    !showing || state !is LyricsTranslationUiState.Ready -> null
    state.byGemini -> KoTranslationDetail.ByGemini
    else -> KoTranslationDetail.Translated
}

@Composable
internal fun koTranslationDetailText(
    state: LyricsTranslationUiState,
    showing: Boolean,
    languageName: String,
): String? = when (koTranslationDetail(state, showing)) {
    KoTranslationDetail.Translating -> stringResource(Res.string.translating_lyrics_to, languageName)
    KoTranslationDetail.ByGemini -> stringResource(Res.string.lyrics_translated_gemini, languageName)
    KoTranslationDetail.Translated -> stringResource(Res.string.lyrics_translated_to, languageName)
    null -> null
}

/** What the sheet says about the timing, in the order it is shown. */
internal enum class KoTimingDetail { Running, Moved, ByHand }

internal fun koTimingDetails(timing: LyricsTimingUi): List<KoTimingDetail> = buildList {
    when {
        timing.aiRunning -> add(KoTimingDetail.Running)
        timing.shiftMs != null || timing.byAi -> add(KoTimingDetail.Moved)
    }
    if (timing.manualMs != 0L) add(KoTimingDetail.ByHand)
}

@Composable
internal fun koTimingDetailTexts(timing: LyricsTimingUi): List<String> =
    koTimingDetails(timing).map { detail ->
        when (detail) {
            KoTimingDetail.Running -> stringResource(
                Res.string.lyrics_timing_sheet_line,
                stringResource(Res.string.lyrics_timing_ai_button_running),
            )
            KoTimingDetail.Moved -> stringResource(
                Res.string.lyrics_timing_sheet_line,
                koLyricsTimingDetail(timing).orEmpty(),
            )
            KoTimingDetail.ByHand -> stringResource(
                Res.string.lyrics_timing_manual_short,
                koSignedSeconds(timing.manualMs),
            )
        }
    }

/** The quiet lines above the provider rows, seen without scrolling. They wrap rather than cut anything off. */
@Composable
internal fun koLyricsSheetDetails(details: List<String>) {
    details.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
        )
    }
}
