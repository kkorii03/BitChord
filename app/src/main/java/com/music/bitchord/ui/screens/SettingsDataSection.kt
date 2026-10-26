package com.music.bitchord.ui.screens

import android.content.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.*
import com.music.bitchord.data.spotify.PlaylistImportMode
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.ui.FriendlyError
import kotlinx.coroutines.launch

@Composable
internal fun SettingsDataSection(
    search: SettingsSearch,
    onOpenReplay: () -> Unit,
) {
    val context = LocalContext.current

    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()
    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    /** The file chosen, waiting for the replace-or-merge choice. */
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val backupScope = rememberCoroutineScope()
    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, FriendlyError.message(context, it))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        // Which file is separate from how its playlists should be applied, and
        // the second question is the one the user has to answer.
        pendingImport = source
    }
    val applyImport: (Uri, PlaylistImportMode) -> Unit = { source, mode ->
        pendingImport = null
        backupScope.launch {
            importStatus = Backup.importFrom(context, source, mode).fold(
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
            )
        }
    }
    // Settings transfer: a short file of proxy, sources and sound/screen
    // choices. Separate from the backup above, which replaces history too.
    var settingsSendStatus by remember { mutableStateOf<String?>(null) }
    var settingsImportStatus by remember { mutableStateOf<String?>(null) }
    var confirmSendSettings by remember { mutableStateOf(false) }
    var settingsPreview by remember { mutableStateOf<SettingsTransfer.Preview?>(null) }

    val settingsImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            SettingsTransfer.importFrom(context, source).fold(
                onSuccess = { settingsPreview = it },
                onFailure = {
                    settingsImportStatus = context.getString(
                        R.string.settings_transfer_import_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

    // A file shared to BitChord from a chat app or file manager. The activity
    // has already brought Settings forward; reading it here is the other half.
    val incomingSettingsFile by SettingsTransferInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(incomingSettingsFile) {
        val source = incomingSettingsFile ?: return@LaunchedEffect
        SettingsTransferInbox.handled()
        SettingsTransfer.importFrom(context, source).fold(
            onSuccess = { settingsPreview = it },
            onFailure = {
                settingsImportStatus = context.getString(
                    R.string.settings_transfer_import_failed,
                    FriendlyError.message(context, it),
                )
            },
        )
    }

    val sendSettings: () -> Unit = {
        confirmSendSettings = false
        backupScope.launch {
            SettingsTransfer.share(context).fold(
                onSuccess = { intent ->
                    settingsSendStatus = context.getString(R.string.settings_transfer_share_opened)
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.settings_transfer_send))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onFailure = {
                    settingsSendStatus = context.getString(
                        R.string.settings_transfer_send_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

    val applySettingsImport: (SettingsTransfer.Preview) -> Unit = { preview ->
        settingsPreview = null
        backupScope.launch {
            SettingsTransfer.apply(preview).fold(
                onSuccess = { summary ->
                    val parts = mutableListOf(
                        context.getString(
                            R.string.settings_transfer_applied,
                            summary.settings,
                            summary.sources,
                        ),
                    )
                    if (summary.proxy) {
                        parts += context.getString(R.string.settings_transfer_applied_proxy)
                    }
                    if (summary.skipped > 0) {
                        parts += context.getString(
                            R.string.settings_transfer_skipped,
                            summary.skipped,
                        )
                    }
                    settingsImportStatus = parts.joinToString(" ")
                },
                onFailure = {
                    settingsImportStatus = context.getString(
                        R.string.settings_transfer_import_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

        SearchableSettingsGroup(search, header = stringResource(R.string.your_data)) {
            val replayTitle = stringResource(R.string.replay)
            row(replayTitle, "stats", "history", "wrapped") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = replayTitle,
                    subtitle = stringResource(R.string.replay_subtitle),
                    onClick = onOpenReplay,
                )
            }
            val workOutGenresTitle = stringResource(R.string.work_out_genres)
            row(workOutGenresTitle, "genre", "replay") {
                SettingsRow(
                    icon = Icons.Rounded.LocalOffer,
                    title = workOutGenresTitle,
                    subtitle = if (replayGenres) {
                        stringResource(R.string.replay_genres_enabled_subtitle)
                    } else {
                        stringResource(R.string.replay_genres_disabled_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = replayGenres,
                            onCheckedChange = AppSettings::setReplayGenres,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setReplayGenres(!replayGenres) },
                )
            }
            val exportDataTitle = stringResource(R.string.export_data)
            row(exportDataTitle, "backup") {
                SettingsRow(
                    icon = Icons.Rounded.FileUpload,
                    title = exportDataTitle,
                    subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                    onClick = { exportPicker.launch(Backup.suggestedName()) },
                )
            }
            val importDataTitle = stringResource(R.string.import_data)
            row(importDataTitle, "backup", "restore") {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = importDataTitle,
                    subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                    onClick = { confirmImport = true },
                )
            }
            val sendSettingsTitle = stringResource(R.string.settings_transfer_send)
            row(sendSettingsTitle, "settings", "transfer", "share", "proxy") {
                SettingsRow(
                    icon = Icons.Rounded.IosShare,
                    title = sendSettingsTitle,
                    subtitle = settingsSendStatus
                        ?: stringResource(R.string.settings_transfer_send_subtitle),
                    onClick = { confirmSendSettings = true },
                )
            }
            val importSettingsTitle = stringResource(R.string.settings_transfer_import)
            row(importSettingsTitle, "settings", "transfer", "import", "proxy") {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = importSettingsTitle,
                    subtitle = settingsImportStatus
                        ?: stringResource(R.string.settings_transfer_import_subtitle),
                    onClick = {
                        settingsImportPicker.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"),
                        )
                    },
                )
            }
        }

    // Asked before the picker opens rather than after a file is chosen: the
    // thing being confirmed is that this device's own history is about to be
    // thrown away, and that is true whichever file gets picked.
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = {
                Text(stringResource(R.string.import_backup_warning))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) {
                    Text(stringResource(R.string.choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // The password leaves the phone in this file, so say so before the share
    // sheet opens rather than after somebody has already sent it.
    if (confirmSendSettings) {
        AlertDialog(
            onDismissRequest = { confirmSendSettings = false },
            title = { Text(stringResource(R.string.settings_transfer_send)) },
            text = { Text(stringResource(R.string.settings_transfer_send_warning)) },
            confirmButton = {
                TextButton(onClick = sendSettings) {
                    Text(stringResource(R.string.settings_transfer_send_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSendSettings = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Nothing has been written yet: this is the list of what would change, and
    // the only button that writes is the one that says so.
    settingsPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { settingsPreview = null },
            title = { Text(stringResource(R.string.settings_transfer_preview_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.settings_transfer_preview_body))
                    if (!preview.hasChanges) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.settings_transfer_preview_none))
                    }
                    if (preview.sourcesChanged) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.settings_transfer_preview_sources,
                                preview.sources.size,
                                preview.sourceNames.joinToString(", "),
                            ),
                        )
                    }
                    if (preview.proxyChanged) {
                        Spacer(Modifier.height(12.dp))
                        val proxy = preview.proxy
                        Text(
                            stringResource(
                                R.string.settings_transfer_preview_proxy,
                                if (proxy == null || proxy.host.isBlank()) {
                                    stringResource(R.string.off)
                                } else {
                                    "${proxy.host}:${proxy.port}"
                                },
                            ),
                        )
                    }
                    if (preview.changedCategoryCounts.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        preview.changedCategoryCounts.forEach { (category, count) ->
                            Text(
                                stringResource(
                                    when (category) {
                                        TransferCategory.QUALITY -> R.string.settings_transfer_preview_quality
                                        TransferCategory.AUTOMIX -> R.string.settings_transfer_preview_automix
                                        TransferCategory.LYRICS -> R.string.settings_transfer_preview_lyrics
                                        TransferCategory.PLAYBACK -> R.string.settings_transfer_preview_playback
                                        TransferCategory.DISPLAY -> R.string.settings_transfer_preview_display
                                    },
                                    count,
                                ),
                            )
                        }
                    }
                    if (preview.skipped > 0) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.settings_transfer_preview_skipped,
                                preview.skipped,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { applySettingsImport(preview) }) {
                    Text(stringResource(R.string.settings_transfer_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { settingsPreview = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // The file is already chosen; the remaining question is only what happens
    // to the local playlists already on this device. Settings, listening and
    // search history are replaced either way.
    pendingImport?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_playlists_title)) },
            text = { Text(stringResource(R.string.import_playlists_choice)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = { applyImport(source, PlaylistImportMode.REPLACE) }) {
                        Text(stringResource(R.string.import_playlists_replace))
                    }
                    TextButton(onClick = { applyImport(source, PlaylistImportMode.MERGE) }) {
                        Text(stringResource(R.string.import_playlists_merge))
                    }
                }
            },
        )
    }
}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}
