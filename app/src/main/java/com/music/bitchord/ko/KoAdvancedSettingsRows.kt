package com.music.bitchord.ko

import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow

@Composable
internal fun SettingsGroupScope.KoAdvancedSyncSafetyRows() {
    val syncSafetyNextPrep by AppSettings.syncSafetyNextPrep.collectAsStateWithLifecycle()
    val syncSafetyDownloadRecheck by AppSettings.syncSafetyDownloadRecheck.collectAsStateWithLifecycle()
    val syncSafetyHqSync by AppSettings.syncSafetyHqSync.collectAsStateWithLifecycle()
    val syncSafetyPerLine by AppSettings.syncSafetyPerLine.collectAsStateWithLifecycle()

    val syncSafetyNextPrepTitle = stringResource(R.string.sync_safety_next_prep)
    row(syncSafetyNextPrepTitle, "lyrics", "sync", "next", "developer", "experimental") {
        SettingsRow(
            icon = SfIcons.Clock,
            title = syncSafetyNextPrepTitle,
            trailing = {
                Switch(
                    checked = syncSafetyNextPrep,
                    onCheckedChange = AppSettings::setSyncSafetyNextPrep,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setSyncSafetyNextPrep(!syncSafetyNextPrep) },
        )
    }
    val syncSafetyDownloadRecheckTitle = stringResource(R.string.sync_safety_download_recheck)
    row(syncSafetyDownloadRecheckTitle, "lyrics", "sync", "download", "recheck", "developer", "experimental") {
        SettingsRow(
            icon = SfIcons.Cached,
            title = syncSafetyDownloadRecheckTitle,
            trailing = {
                Switch(
                    checked = syncSafetyDownloadRecheck,
                    onCheckedChange = AppSettings::setSyncSafetyDownloadRecheck,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setSyncSafetyDownloadRecheck(!syncSafetyDownloadRecheck) },
        )
    }
    val syncSafetyHqSyncTitle = stringResource(R.string.sync_safety_hq_sync)
    row(syncSafetyHqSyncTitle, "lyrics", "sync", "hq", "developer", "experimental") {
        SettingsRow(
            icon = SfIcons.GraphicEq,
            title = syncSafetyHqSyncTitle,
            trailing = {
                Switch(
                    checked = syncSafetyHqSync,
                    onCheckedChange = AppSettings::setSyncSafetyHqSync,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setSyncSafetyHqSync(!syncSafetyHqSync) },
        )
    }
    val syncSafetyPerLineTitle = stringResource(R.string.sync_safety_per_line)
    row(syncSafetyPerLineTitle, "lyrics", "sync", "line", "developer", "experimental") {
        SettingsRow(
            icon = SfIcons.Clock,
            title = syncSafetyPerLineTitle,
            trailing = {
                Switch(
                    checked = syncSafetyPerLine,
                    onCheckedChange = AppSettings::setSyncSafetyPerLine,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setSyncSafetyPerLine(!syncSafetyPerLine) },
        )
    }
}
