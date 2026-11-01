package com.music.bitchord.ko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.startup.StartupTrace

/**
 * One line, once: how long from process start to real Home content. Kept out
 * of the upstream file — see docs/ko-fork-layout.md.
 */
@Composable
internal fun KoFirstHomeContentEffect(hasContent: Boolean) {
    LaunchedEffect(hasContent) {
        if (hasContent) StartupTrace.firstHomeContent()
    }
}
