package com.music.bitchord.ko

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.ui.screens.Chevron
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow
import com.music.bitchord.ui.screens.countryDisplayName

@Composable
internal fun SettingsGroupScope.KoContentCountryRow(onContentCountry: () -> Unit) {
    val contentCountry by AppSettings.contentCountry.collectAsStateWithLifecycle()
    val contentCountryTitle = stringResource(R.string.content_country)
    row(contentCountryTitle, "country", "region", "chart", "gl", "국가", "지역") {
        SettingsRow(
            icon = Icons.Rounded.Public,
            title = contentCountryTitle,
            subtitle = countryDisplayName(contentCountry),
            trailing = { Chevron() },
            onClick = onContentCountry,
        )
    }
}

@StringRes
internal fun koOutputRouteLabelRes(kind: AudioRouting.Kind): Int = when (kind) {
    AudioRouting.Kind.PHONE -> R.string.route_phone
    AudioRouting.Kind.WIRED -> R.string.route_wired
    AudioRouting.Kind.USB -> R.string.route_usb
    AudioRouting.Kind.BLUETOOTH -> R.string.route_bluetooth
    AudioRouting.Kind.HDMI -> R.string.route_hdmi
    AudioRouting.Kind.OTHER -> R.string.route_other
}
