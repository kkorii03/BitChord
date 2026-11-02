package com.music.bitchord.ko

import com.music.bitchord.ui.icons.SfIcons
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.SettingsTransfer
import com.music.bitchord.data.settings.SettingsTransferInbox
import com.music.bitchord.data.settings.TransferCategory
import com.music.bitchord.ui.FriendlyError
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class KoSettingsTransferState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var sendStatus by mutableStateOf<String?>(null)
    var importStatus by mutableStateOf<String?>(null)
    var confirmSend by mutableStateOf(false)
    var preview by mutableStateOf<SettingsTransfer.Preview?>(null)
    internal var launchImport: (() -> Unit)? = null

    fun send() {
        confirmSend = false
        scope.launch {
            SettingsTransfer.share(context).fold(
                onSuccess = { intent ->
                    sendStatus = context.getString(R.string.settings_transfer_share_opened)
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.settings_transfer_send))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onFailure = {
                    sendStatus = context.getString(
                        R.string.settings_transfer_send_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }

    fun apply(preview: SettingsTransfer.Preview) {
        this.preview = null
        scope.launch {
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
                    importStatus = parts.joinToString(" ")
                },
                onFailure = {
                    importStatus = context.getString(
                        R.string.settings_transfer_import_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }
}

@Composable
internal fun rememberKoSettingsTransferState(
    context: Context,
    scope: CoroutineScope,
): KoSettingsTransferState {
    val state = remember(context, scope) { KoSettingsTransferState(context, scope) }
    val settingsImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        scope.launch {
            SettingsTransfer.importFrom(context, source).fold(
                onSuccess = { state.preview = it },
                onFailure = {
                    state.importStatus = context.getString(
                        R.string.settings_transfer_import_failed,
                        FriendlyError.message(context, it),
                    )
                },
            )
        }
    }
    state.launchImport = {
        settingsImportPicker.launch(
            arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"),
        )
    }

    // A file shared to BitChord from a chat app or file manager. The activity
    // has already brought Settings forward; reading it here is the other half.
    val incomingSettingsFile by SettingsTransferInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(incomingSettingsFile) {
        val source = incomingSettingsFile ?: return@LaunchedEffect
        SettingsTransferInbox.handled()
        SettingsTransfer.importFrom(context, source).fold(
            onSuccess = { state.preview = it },
            onFailure = {
                state.importStatus = context.getString(
                    R.string.settings_transfer_import_failed,
                    FriendlyError.message(context, it),
                )
            },
        )
    }
    return state
}

@Composable
internal fun SettingsGroupScope.KoSettingsTransferRows(state: KoSettingsTransferState) {
    val sendSettingsTitle = stringResource(R.string.settings_transfer_send)
    row(sendSettingsTitle, "settings", "transfer", "share", "proxy") {
        SettingsRow(
            icon = SfIcons.Share,
            title = sendSettingsTitle,
            subtitle = state.sendStatus
                ?: stringResource(R.string.settings_transfer_send_subtitle),
            onClick = { state.confirmSend = true },
        )
    }
    val importSettingsTitle = stringResource(R.string.settings_transfer_import)
    row(importSettingsTitle, "settings", "transfer", "import", "proxy") {
        SettingsRow(
            icon = SfIcons.FileDownload,
            title = importSettingsTitle,
            subtitle = state.importStatus
                ?: stringResource(R.string.settings_transfer_import_subtitle),
            onClick = { state.launchImport?.invoke() },
        )
    }
}

@Composable
internal fun KoSettingsTransferDialogs(state: KoSettingsTransferState) {
    // The password and any token in a source URL leave the phone in this file,
    // so say so before the share sheet opens rather than after somebody has
    // already sent it.
    if (state.confirmSend) {
        AlertDialog(
            onDismissRequest = { state.confirmSend = false },
            title = { Text(stringResource(R.string.settings_transfer_send)) },
            text = { Text(stringResource(R.string.settings_transfer_send_warning)) },
            confirmButton = {
                TextButton(onClick = { state.send() }) {
                    Text(stringResource(R.string.settings_transfer_send_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.confirmSend = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Nothing has been written yet: this is the list of what would change, and
    // the only button that writes is the one that says so.
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { state.preview = null },
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
                TextButton(onClick = { state.apply(preview) }) {
                    Text(stringResource(R.string.settings_transfer_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.preview = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
