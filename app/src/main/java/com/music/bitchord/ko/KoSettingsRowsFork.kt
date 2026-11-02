package com.music.bitchord.ko

import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.screens.SettingsRow

@Composable
internal fun KoWifiQualityRow(
    title: String,
    value: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    SettingsRow(
        icon = SfIcons.Wifi,
        title = title,
        value = value,
        badge = badge,
        onClick = onClick,
    )
}

@Composable
internal fun KoCellularQualityRow(
    title: String,
    value: String,
    badge: String? = null,
    onClick: () -> Unit,
) {
    SettingsRow(
        icon = SfIcons.SignalCellularAlt,
        title = title,
        value = value,
        badge = badge,
        onClick = onClick,
    )
}

@Composable
internal fun KoDolbyAtmosRow(
    title: String,
    checked: Boolean,
    supported: Boolean,
) {
    SettingsRow(
        iconPainter = painterResource(R.drawable.ic_dolby_atmos),
        title = title,
        subtitle = stringResource(
            if (supported) {
                R.string.dolby_atmos_subtitle
            } else {
                R.string.dolby_atmos_unavailable
            },
        ),
        enabled = supported,
        trailing = {
            Switch(
                checked = checked && supported,
                onCheckedChange = AppSettings::setDolbyAtmos,
                enabled = supported,
                colors = settingsSwitchColors(),
            )
        },
        onClick = { AppSettings.setDolbyAtmos(!checked) },
    )
}

@Composable
internal fun KoSkipSilenceRow(
    title: String,
    checked: Boolean,
) {
    SettingsRow(
        icon = SfIcons.VolumeOff,
        title = title,
        // The low-level Media3 processor only runs on its 16-bit branch, so
        // the precision sink now trims float and direct output itself before
        // the DSP chain. The switch is live on both paths; only the
        // implementation differs.
        subtitle = stringResource(R.string.skip_silence_subtitle),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = AppSettings::setSkipSilence,
                colors = settingsSwitchColors(),
            )
        },
        onClick = { AppSettings.setSkipSilence(!checked) },
    )
}

@Composable
internal fun KoSpatialAudioRow(
    title: String,
    checked: Boolean,
    playingDolbyAtmos: Boolean,
) {
    SettingsRow(
        icon = SfIcons.SurroundSound,
        title = title,
        // Widening a JOC stream would fight the object-based mix Dolby has
        // already spatialized, so the service holds this off for the duration
        // of an Atmos track — see `PlaybackService.applySpatialAudioEnabled`.
        // Said on the row because the switch stays where the listener left it,
        // and a switch reading "on" over an effect that is not running is the
        // same silent lie the equaliser screen used to tell.
        subtitle = if (playingDolbyAtmos) {
            stringResource(R.string.spatial_audio_atmos_subtitle)
        } else {
            stringResource(R.string.spatial_audio_subtitle)
        },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = AppSettings::setSpatialAudio,
                colors = settingsSwitchColors(),
            )
        },
        onClick = { AppSettings.setSpatialAudio(!checked) },
    )
}

@Composable
internal fun KoAutomixRow(
    title: String,
    checked: Boolean,
) {
    SettingsRow(
        icon = SfIcons.AutoAwesome,
        title = title,
        subtitle = stringResource(
            if (checked) R.string.automix_enabled_subtitle else R.string.automix_disabled_subtitle,
        ),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = AppSettings::setSmartFadeEnabled,
                colors = settingsSwitchColors(),
            )
        },
        onClick = { AppSettings.setSmartFadeEnabled(!checked) },
    )
}

@Composable
internal fun KoPreferMusicOnlyRow(
    title: String,
    checked: Boolean,
) {
    SettingsRow(
        icon = SfIcons.SmartDisplay,
        title = title,
        subtitle = stringResource(R.string.prefer_music_only_subtitle),
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = AppSettings::setPreferMusicOnly,
                colors = settingsSwitchColors(),
            )
        },
        onClick = { AppSettings.setPreferMusicOnly(!checked) },
    )
}
