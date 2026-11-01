package com.music.bitchord.ko

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PrivateListeningSource
import com.music.bitchord.ui.screens.SettingsGroupScope
import com.music.bitchord.ui.screens.SettingsRow
import com.music.bitchord.ui.screens.SettingsSubRow

@Composable
internal fun SettingsGroupScope.KoPlaybackTopRows() {
    val resumeOnHeadphones by AppSettings.resumeOnHeadphones.collectAsStateWithLifecycle()
    val personalisedAutoplay by AppSettings.personalisedAutoplay.collectAsStateWithLifecycle()
    val videoRadio by AppSettings.videoRadio.collectAsStateWithLifecycle()

    val resumeOnHeadphonesTitle = stringResource(R.string.resume_on_headphones)
    row(resumeOnHeadphonesTitle, "headphones", "earbuds", "bluetooth", "resume", "이어폰") {
        SettingsRow(
            icon = Icons.Rounded.Headphones,
            title = resumeOnHeadphonesTitle,
            trailing = {
                Switch(
                    checked = resumeOnHeadphones,
                    onCheckedChange = { AppSettings.setResumeOnHeadphones(it) },
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setResumeOnHeadphones(!resumeOnHeadphones) },
        )
    }
    val personalisedAutoplayTitle = stringResource(R.string.personalised_autoplay)
    row(personalisedAutoplayTitle, "autoplay", "radio", "recommend", "추천") {
        SettingsRow(
            icon = Icons.Rounded.AutoAwesome,
            title = personalisedAutoplayTitle,
            trailing = {
                Switch(
                    checked = personalisedAutoplay,
                    onCheckedChange = AppSettings::setPersonalisedAutoplay,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setPersonalisedAutoplay(!personalisedAutoplay) },
        )
    }
    val videoRadioTitle = stringResource(R.string.video_radio_continue)
    row(videoRadioTitle, "video", "radio", "cover", "비디오") {
        SettingsRow(
            icon = Icons.Rounded.SmartDisplay,
            title = videoRadioTitle,
            trailing = {
                Switch(
                    checked = videoRadio,
                    onCheckedChange = AppSettings::setVideoRadio,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setVideoRadio(!videoRadio) },
        )
    }
}

@Composable
internal fun SettingsGroupScope.KoSkipPartsRows() {
    val skipNonMusicParts by AppSettings.skipNonMusicParts.collectAsStateWithLifecycle()
    val skipSponsorParts by AppSettings.skipSponsorParts.collectAsStateWithLifecycle()

    val skipNonMusicPartsTitle = stringResource(R.string.skip_non_music_parts)
    row(skipNonMusicPartsTitle, "sponsor", "skip", "intro", "outro", "건너뛰기") {
        SettingsRow(
            icon = Icons.Rounded.MusicOff,
            title = skipNonMusicPartsTitle,
            subtitle = stringResource(R.string.skip_non_music_parts_subtitle),
            trailing = {
                Switch(
                    checked = skipNonMusicParts,
                    onCheckedChange = AppSettings::setSkipNonMusicParts,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setSkipNonMusicParts(!skipNonMusicParts) },
        )
    }
    if (skipNonMusicParts) {
        val skipSponsorPartsTitle = stringResource(R.string.skip_sponsor_parts)
        row(skipSponsorPartsTitle, "sponsor", "self promo", "intro", "outro", divided = false) {
            SettingsSubRow(
                title = skipSponsorPartsTitle,
                subtitle = stringResource(R.string.skip_sponsor_parts_subtitle),
                checked = skipSponsorParts,
                onCheckedChange = AppSettings::setSkipSponsorParts,
            )
        }
    }
}

@Composable
internal fun SettingsGroupScope.KoAutomixVideosRow() {
    val automixVideos by AppSettings.automixVideos.collectAsStateWithLifecycle()
    val automixVideosTitle = stringResource(R.string.automix_videos)
    row(automixVideosTitle, "video", "automix", "mix") {
        SettingsSubRow(
            title = automixVideosTitle,
            subtitle = stringResource(R.string.automix_videos_subtitle),
            checked = automixVideos,
            onCheckedChange = AppSettings::setAutomixVideos,
        )
    }
}

@Composable
internal fun SettingsGroupScope.KoPlaybackPrivacyRows() {
    val privateListening by AppSettings.privateListening.collectAsStateWithLifecycle()
    val widgetOpensPlayer by AppSettings.widgetOpensPlayer.collectAsStateWithLifecycle()

    val privateListeningTitle = stringResource(R.string.private_listening)
    row(privateListeningTitle, "private", "incognito", "history", "privacy") {
        SettingsRow(
            icon = Icons.Rounded.VisibilityOff,
            title = privateListeningTitle,
            subtitle = stringResource(R.string.private_listening_subtitle),
            trailing = {
                Switch(
                    checked = privateListening,
                    onCheckedChange = {
                        AppSettings.setPrivateListening(it, PrivateListeningSource.SETTINGS)
                    },
                    colors = settingsSwitchColors(),
                )
            },
            onClick = {
                AppSettings.setPrivateListening(
                    !privateListening,
                    PrivateListeningSource.SETTINGS,
                )
            },
        )
    }
    val widgetOpensPlayerTitle = stringResource(R.string.widget_opens_player)
    row(widgetOpensPlayerTitle, "widget", "home screen", "player") {
        SettingsRow(
            icon = Icons.Rounded.Widgets,
            title = widgetOpensPlayerTitle,
            subtitle = stringResource(R.string.widget_opens_player_subtitle),
            trailing = {
                Switch(
                    checked = widgetOpensPlayer,
                    onCheckedChange = AppSettings::setWidgetOpensPlayer,
                    colors = settingsSwitchColors(),
                )
            },
            onClick = { AppSettings.setWidgetOpensPlayer(!widgetOpensPlayer) },
        )
    }
}
