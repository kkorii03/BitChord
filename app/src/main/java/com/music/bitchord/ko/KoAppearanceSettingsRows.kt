package com.music.bitchord.ko

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LowPowerMode
import com.music.bitchord.ui.screens.ROW_INSET
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow
import com.music.bitchord.ui.screens.SettingsSegmentedControl
import com.music.bitchord.ui.screens.SettingsSubRow

@Composable
internal fun SettingsGroupScope.KoLowPowerRow() {
    val lowPowerMode by AppSettings.lowPowerMode.collectAsStateWithLifecycle()
    val lowPowerTitle = stringResource(R.string.low_power_mode)
    row(lowPowerTitle, "battery", "heat", "power saving") {
        SettingsRow(
            icon = Icons.Rounded.BatterySaver,
            title = lowPowerTitle,
            subtitle = stringResource(R.string.low_power_mode_subtitle),
        )
        SettingsSegmentedControl(
            options = LowPowerMode.entries.map { it.koLocalizedLabel() },
            selectedIndex = LowPowerMode.entries.indexOf(lowPowerMode),
            onSelect = { AppSettings.setLowPowerMode(LowPowerMode.entries[it]) },
            modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
        )
    }
}

@Composable
private fun LowPowerMode.koLocalizedLabel(): String = stringResource(
    when (this) {
        LowPowerMode.AUTO -> R.string.low_power_auto
        LowPowerMode.ON -> R.string.low_power_on
        LowPowerMode.OFF -> R.string.low_power_off
    },
)

@Composable
internal fun SettingsGroupScope.KoVideoCanvasRow() {
    val videoCanvas by AppSettings.videoCanvas.collectAsStateWithLifecycle()
    val videoCanvasTitle = stringResource(R.string.video_canvas)
    row(videoCanvasTitle, "canvas", "video", "music video", divided = false) {
        SettingsSubRow(
            title = videoCanvasTitle,
            checked = videoCanvas,
            onCheckedChange = AppSettings::setVideoCanvas,
        )
    }
}
