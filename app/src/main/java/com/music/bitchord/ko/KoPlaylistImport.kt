package com.music.bitchord.ko

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.spotify.PlaylistImportMode
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.ui.FriendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class KoPlaylistImportState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (String?) -> Unit,
) {
    var pending by mutableStateOf<Uri?>(null)

    fun onPicked(source: Uri) {
        pending = source
    }

    fun apply(source: Uri, mode: PlaylistImportMode) {
        pending = null
        scope.launch {
            onStatus(
                Backup.importFrom(context, source, mode).fold(
                    onSuccess = { summary ->
                        if (summary.playlists > 0) {
                            context.getString(
                                R.string.import_succeeded_with_library,
                                context.countOfMonths(summary.months),
                                summary.from,
                                summary.playlists,
                            )
                        } else {
                            context.getString(
                                R.string.import_succeeded,
                                context.countOfMonths(summary.months),
                                summary.from,
                            )
                        }
                    },
                    onFailure = {
                        context.getString(R.string.import_failed, FriendlyError.message(context, it))
                    },
                ),
            )
        }
    }
}

@Composable
internal fun rememberKoPlaylistImportState(
    context: Context,
    scope: CoroutineScope,
    onStatus: (String?) -> Unit,
): KoPlaylistImportState = remember(context, scope) {
    KoPlaylistImportState(context, scope, onStatus)
}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

@Composable
internal fun KoPlaylistImportDialog(state: KoPlaylistImportState) {
    // The file is already chosen; the remaining question is only what happens
    // to the local playlists already on this device. Settings, listening and
    // search history are replaced either way.
    state.pending?.let { source ->
        AlertDialog(
            onDismissRequest = { state.pending = null },
            title = { Text(stringResource(R.string.import_playlists_title)) },
            text = { Text(stringResource(R.string.import_playlists_choice)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { state.pending = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = { state.apply(source, PlaylistImportMode.REPLACE) }) {
                        Text(stringResource(R.string.import_playlists_replace))
                    }
                    TextButton(onClick = { state.apply(source, PlaylistImportMode.MERGE) }) {
                        Text(stringResource(R.string.import_playlists_merge))
                    }
                }
            },
        )
    }
}
