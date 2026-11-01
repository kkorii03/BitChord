package com.music.bitchord.ko.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricLineIndex

internal class KoLyricPanelIndexes(
    val rowIndex: LyricLineIndex,
    val leadIndex: LyricLineIndex,
    val activeRows: State<List<Int>>,
    val activeRowSet: Set<Int>,
    val leadLine: State<Int>,
)

/**
 * The panel's row and lead indexes. The clock moves every frame; the line it
 * picks out does not. These answer from a binary search and a backwards walk
 * over the vocals still unfinished, so a tick is a handful of comparisons
 * rather than a scan and a full-list allocation. Kept apart because the lead
 * line asks at a different position from the rows, and one shared scratch
 * buffer would make the two evict each other every frame.
 */
@Composable
internal fun rememberKoLyricPanelIndexes(
    lines: List<LyricLine>,
    isSynced: Boolean,
    clock: MutableLongState,
    scrollLead: (List<LyricLine>, Int) -> Long,
): KoLyricPanelIndexes {
    val rowIndex = remember(lines) { LyricLineIndex(lines) }
    val leadIndex = remember(lines) { LyricLineIndex(lines) }
    val activeRows = remember(lines, isSynced, rowIndex) {
        derivedStateOf {
            if (!isSynced) emptyList() else rowIndex.activeRows(clock.longValue)
        }
    }
    // Rows ask "am I active" one by one, so membership is a set lookup keyed on
    // the row list rather than a scan; it is rebuilt only when the answer
    // itself changes.
    val rows by activeRows
    val activeRowSet = remember(rows) { rows.toHashSet() }
    val leadLine = remember(lines, isSynced, leadIndex) {
        derivedStateOf {
            if (!isSynced) {
                -1
            } else {
                val now = clock.longValue
                val lead = scrollLead(lines, leadIndex.lineAt(now))
                leadIndex.activeRows(now + lead).firstOrNull() ?: -1
            }
        }
    }
    return remember(rowIndex, leadIndex, activeRows, activeRowSet, leadLine) {
        KoLyricPanelIndexes(rowIndex, leadIndex, activeRows, activeRowSet, leadLine)
    }
}
