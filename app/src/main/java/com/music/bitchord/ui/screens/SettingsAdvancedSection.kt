package com.music.bitchord.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.AppIcon
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

@Composable
internal fun SettingsAdvancedSection(search: SettingsSearch) {
    val context = LocalContext.current

    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val syncSafetyNextPrep by AppSettings.syncSafetyNextPrep.collectAsStateWithLifecycle()
    val syncSafetyDownloadRecheck by AppSettings.syncSafetyDownloadRecheck.collectAsStateWithLifecycle()
    val syncSafetyHqSync by AppSettings.syncSafetyHqSync.collectAsStateWithLifecycle()
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()

        SearchableSettingsGroup(search, header = stringResource(R.string.advanced_options)) {
            val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
            row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = smartAlignmentTitle,
                    subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                    trailing = {
                        Switch(
                            checked = smartVersionAlignment,
                            onCheckedChange = AppSettings::setSmartVersionAlignment,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
                )
            }
            val syncSafetyNextPrepTitle = stringResource(R.string.sync_safety_next_prep)
            row(syncSafetyNextPrepTitle, "lyrics", "sync", "next", "developer", "experimental") {
                SettingsRow(
                    icon = Icons.Rounded.Timer,
                    title = syncSafetyNextPrepTitle,
                    subtitle = stringResource(R.string.sync_safety_next_prep_subtitle),
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
                    icon = Icons.Rounded.Cached,
                    title = syncSafetyDownloadRecheckTitle,
                    subtitle = stringResource(R.string.sync_safety_download_recheck_subtitle),
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
                    icon = Icons.Rounded.GraphicEq,
                    title = syncSafetyHqSyncTitle,
                    subtitle = stringResource(R.string.sync_safety_hq_sync_subtitle),
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
            val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
            row(showNerdStatsTitle, "debug", "bitrate", "codec") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = showNerdStatsTitle,
                    subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                    trailing = {
                        Switch(
                            checked = nerdStats,
                            onCheckedChange = AppSettings::setShowNerdStats,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
            val appIconTitle = stringResource(R.string.app_icon)
            row(appIconTitle, "icon", "launcher", "logo") {
                var selectedIcon by remember { mutableStateOf(AppIcon.current(context)) }
                SettingsRow(icon = Icons.Rounded.Apps, title = appIconTitle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon.entries.forEach { option ->
                        val selected = option == selectedIcon
                        Column(
                            modifier = Modifier
                                .width(84.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedIcon = option
                                    AppIcon.apply(context, option)
                                }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // A glass foreground holds the finished icon in its own shape on the
                            // central 72 of 108 dp: scaled 1.5× it fills the tile as designed.
                            // Only the default goes through an adaptive mask.
                            val preview = option.previewRes
                            if (preview == null) {
                                AndroidView(
                                    factory = { ctx ->
                                        android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) }
                                    },
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(22.dp)),
                                )
                            } else {
                                Image(
                                    painter = painterResource(preview),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .scale(1.5f),
                                )
                            }
                            Text(
                                text = stringResource(option.labelRes),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
}
