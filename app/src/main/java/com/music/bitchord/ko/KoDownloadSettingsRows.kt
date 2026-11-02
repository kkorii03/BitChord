package com.music.bitchord.ko

import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadHealth
import com.music.bitchord.download.HqLibraryUpgrade
import com.music.bitchord.download.MvSoundRedownload
import com.music.bitchord.ui.screens.Chevron
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow
import com.music.bitchord.ui.screens.SettingsSubRow

@Composable
internal fun SettingsGroupScope.KoDownloadExtrasRows() {
    val context = LocalContext.current
    val autoRedownloadMvSound by AppSettings.autoRedownloadMvSound.collectAsStateWithLifecycle()
    val autoReplaceHqDownloads by AppSettings.autoReplaceHqDownloads.collectAsStateWithLifecycle()
    val autoPrefetchHq by AppSettings.autoPrefetchHq.collectAsStateWithLifecycle()
    val hqPrefetchLimit by AppSettings.hqPrefetchLimit.collectAsStateWithLifecycle()

    val autoRedownloadMvSoundTitle = stringResource(R.string.auto_redownload_mv_sound)
    val autoRedownloadMvSoundSubtitle =
        stringResource(R.string.auto_redownload_mv_sound_subtitle)
    row(autoRedownloadMvSoundTitle, "mv", "video", "download", "automatic", divided = false) {
        SettingsSubRow(
            title = autoRedownloadMvSoundTitle,
            subtitle = autoRedownloadMvSoundSubtitle,
            checked = autoRedownloadMvSound,
            onCheckedChange = { checked ->
                AppSettings.setAutoRedownloadMvSound(checked)
                if (checked) {
                    MvSoundRedownload.scheduleIfEnabled(context)
                } else {
                    MvSoundRedownload.cancel(context)
                }
            },
        )
    }
    val autoReplaceHqTitle = stringResource(R.string.auto_replace_hq_downloads)
    val autoReplaceHqSubtitle =
        stringResource(R.string.auto_replace_hq_downloads_subtitle)
    row(autoReplaceHqTitle, "hq", "high quality", "lossless", "replace", "고음질", divided = false) {
        SettingsSubRow(
            title = autoReplaceHqTitle,
            subtitle = autoReplaceHqSubtitle,
            checked = autoReplaceHqDownloads,
            onCheckedChange = { checked ->
                AppSettings.setAutoReplaceHqDownloads(checked)
                HqLibraryUpgrade.onSettingsChanged(context)
            },
        )
    }
    val autoPrefetchHqTitle = stringResource(R.string.auto_prefetch_hq)
    val autoPrefetchHqSubtitle =
        stringResource(R.string.auto_prefetch_hq_subtitle, hqPrefetchLimit)
    row(autoPrefetchHqTitle, "hq", "high quality", "prefetch", "offline", "고음질", divided = false) {
        SettingsSubRow(
            title = autoPrefetchHqTitle,
            subtitle = autoPrefetchHqSubtitle,
            checked = autoPrefetchHq,
            onCheckedChange = { checked ->
                AppSettings.setAutoPrefetchHq(checked)
                HqLibraryUpgrade.onSettingsChanged(context)
            },
        )
    }
    val recheckDownloadsTitle = stringResource(R.string.download_health_recheck)
    val recheckDownloadsSubtitle =
        stringResource(R.string.download_health_recheck_subtitle)
    row(recheckDownloadsTitle, "download", "recheck", "verify", "다운로드", "점검", divided = false) {
        SettingsRow(
            icon = SfIcons.Cached,
            title = recheckDownloadsTitle,
            subtitle = recheckDownloadsSubtitle,
            trailing = { Chevron() },
            onClick = { DownloadHealth.resetChecks(context) },
        )
    }
}

@Composable
internal fun SettingsGroupScope.KoAutoDownloadLikedRow() {
    val autoDownloadLiked by AppSettings.autoDownloadLiked.collectAsStateWithLifecycle()
    val autoDownloadLikedTitle = stringResource(R.string.auto_download_liked)
    val autoDownloadLikedSubtitle =
        stringResource(R.string.auto_download_liked_subtitle)
    row(autoDownloadLikedTitle, "like", "heart", "offline", divided = false) {
        SettingsSubRow(
            title = autoDownloadLikedTitle,
            subtitle = autoDownloadLikedSubtitle,
            checked = autoDownloadLiked,
            onCheckedChange = AppSettings::setAutoDownloadLiked,
        )
    }
}
