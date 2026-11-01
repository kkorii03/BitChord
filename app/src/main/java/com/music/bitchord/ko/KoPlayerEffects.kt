package com.music.bitchord.ko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * An emptied queue leaves the player sheet with nothing to show. Moved out of
 * MainActivity so the upstream player code stays untouched — see
 * docs/ko-fork-layout.md.
 */
@Composable
internal fun KoDismissEmptyPlayerEffect(
    songId: String?,
    hasSong: Boolean,
    showNowPlaying: Boolean,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(songId) {
        if (!hasSong && showNowPlaying) onDismiss()
    }
}
