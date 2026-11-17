package com.music.bitchord.ko.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.ui.player.LyricsDisplayMode
import com.music.bitchord.ui.player.LyricsTranslationResult
import com.music.bitchord.ui.player.LyricsTranslationUiState
import com.music.bitchord.ui.player.PlayerPlatform

@Composable
internal fun koRetimedLyricsEffect(
    lyrics: List<LyricLine>?,
    trackId: String,
    translationLanguage: String,
    translationState: LyricsTranslationUiState,
    onTranslation: (List<LyricLine>, Boolean) -> Unit,
) {
    // Same words, new timings: what is on screen is made again for the moved
    // lines — from the cache, at once — and swapped in quietly, without
    // folding the small lines away and back or a haptic for a tap nobody made.
    // Also re-checked when a translation finishes loading: it was built from
    // the list the tap saw, and a re-timing that landed meanwhile would
    // otherwise leave it on the old clock for the rest of the song.
    LaunchedEffect(koRetimeKey(lyrics, translationState)) {
        val source = lyrics
        if (source.isNullOrEmpty()) return@LaunchedEffect
        val translated = translationState as? LyricsTranslationUiState.Ready
        if (translated != null && !koSameTiming(translated.lines, source)) {
            val result = PlayerPlatform.host.translateLyrics(
                trackId = trackId,
                lines = source,
                targetLanguageTag = translationLanguage,
            )
            if (result is LyricsTranslationResult.Translated) {
                onTranslation(result.lines, result.byGemini)
            }
        }
    }
}

/**
 * What re-runs the re-time check: a new list, or a translation that became
 * Ready. Loading and the other states share one key, so they do not re-run it.
 */
internal fun koRetimeKey(
    lyrics: List<LyricLine>?,
    state: LyricsTranslationUiState,
): Pair<List<LyricLine>?, LyricsTranslationUiState.Ready?> =
    lyrics to (state as? LyricsTranslationUiState.Ready)

@Composable
internal fun koAutoTranslateEffect(
    trackId: String,
    translationLanguage: String,
    lyrics: List<LyricLine>?,
    autoTranslate: Boolean,
    translationState: LyricsTranslationUiState,
    displayMode: LyricsDisplayMode,
    requestTranslation: (Boolean) -> Unit,
) {
    // Auto-translate: each new lyric sheet asks for its translation once, as
    // if the button had been pressed. Keyed on the sheet, so turning the
    // translation off by hand is left alone for the rest of the song.
    LaunchedEffect(trackId, translationLanguage, lyrics, autoTranslate) {
        if (
            autoTranslate &&
            !lyrics.isNullOrEmpty() &&
            translationState is LyricsTranslationUiState.Idle &&
            displayMode == LyricsDisplayMode.Original
        ) {
            requestTranslation(true)
        }
    }
}
