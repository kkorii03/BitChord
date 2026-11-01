package com.music.bitchord.ko.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricLineIndex

@Composable
internal fun rememberKoLineIndex(lines: List<LyricLine>, clock: MutableLongState): State<Int> {
    val rowIndex = remember(lines) { LyricLineIndex(lines) }
    return remember(lines, rowIndex) { derivedStateOf { rowIndex.lineAt(clock.longValue) } }
}
