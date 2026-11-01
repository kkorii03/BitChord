package com.music.bitchord.ko

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.ui.screens.LyricsSettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The Lyrics settings route, kept out of the upstream file. */
@Composable
internal fun KoLyricsSettingsRoute(
    contentPadding: PaddingValues,
    context: Context,
    scope: CoroutineScope,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onGeminiKey: () -> Unit,
    onDeepgramKey: () -> Unit,
) {
    LyricsSettingsScreen(
        onLyricsSources = onLyricsSources,
        onTranslationLanguage = onTranslationLanguage,
        onGeminiKey = onGeminiKey,
        onDeepgramKey = onDeepgramKey,
        onResetDownloadSync = {
            scope.launch {
                LyricsTiming.resetDownloadedSync(context.applicationContext)
            }
        },
        contentPadding = contentPadding,
    )
}
