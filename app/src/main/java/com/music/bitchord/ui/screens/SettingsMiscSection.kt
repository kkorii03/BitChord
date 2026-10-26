package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

@Composable
internal fun SettingsMiscSection(search: SettingsSearch) {
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val showQueueHistory by AppSettings.showQueueHistory.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()

        // No footer: it only restated the stop-on-close row's own subtitle,
        // which sits four rows above it and says the same thing in fewer words.
        SearchableSettingsGroup(search, header = stringResource(R.string.miscellaneous)) {
            val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
            row(playNextOnSwipeTitle, "swipe", "queue") {
                SettingsRow(
                    icon = Icons.Rounded.PlaylistPlay,
                    title = playNextOnSwipeTitle,
                    subtitle = if (swipeToPlayNext) {
                        stringResource(R.string.swipe_plays_next)
                    } else {
                        stringResource(R.string.swipe_adds_to_queue)
                    },
                    trailing = {
                        Switch(
                            checked = swipeToPlayNext,
                            onCheckedChange = AppSettings::setSwipeToPlayNext,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
                )
            }
            val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
            row(dontRepeatSongsTitle, "radio", "suggestions") {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = dontRepeatSongsTitle,
                    subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                    trailing = {
                        Switch(
                            checked = dontRepeatSuggestions,
                            onCheckedChange = AppSettings::setDontRepeatSuggestions,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
                )
            }
            val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
            row(stopMusicOnCloseTitle, "notification", "background") {
                SettingsRow(
                    icon = Icons.Rounded.MusicOff,
                    title = stopMusicOnCloseTitle,
                    subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                    trailing = {
                        Switch(
                            checked = stopOnTaskRemoved,
                            onCheckedChange = AppSettings::setStopOnTaskRemoved,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
                )
            }
            val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
            row(hideVolumeBarTitle, "volume", "player") {
                SettingsRow(
                    icon = Icons.Rounded.VolumeOff,
                    title = hideVolumeBarTitle,
                    subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                    trailing = {
                        Switch(
                            checked = hideVolumeBar,
                            onCheckedChange = AppSettings::setHideVolumeBar,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
                )
            }
            val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
            row(hideStatusBarTitle, "status bar", "immersive", "fullscreen", "player") {
                SettingsRow(
                    icon = Icons.Rounded.WebAssetOff,
                    title = hideStatusBarTitle,
                    subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                    trailing = {
                        Switch(
                            checked = hideStatusBarInPlayer,
                            onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
                )
            }
            val showQueueHistoryTitle = stringResource(R.string.show_queue_history)
            row(showQueueHistoryTitle, "player", "queue", "history", "previously played") {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = showQueueHistoryTitle,
                    subtitle = stringResource(R.string.show_queue_history_subtitle),
                    trailing = {
                        Switch(
                            checked = showQueueHistory,
                            onCheckedChange = AppSettings::setShowQueueHistory,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setShowQueueHistory(!showQueueHistory) },
                )
            }
            val hideSongStatusTitle = stringResource(R.string.hide_song_status)
            row(hideSongStatusTitle, "player", "playing from", "played by") {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = hideSongStatusTitle,
                    subtitle = stringResource(R.string.hide_song_status_subtitle),
                    trailing = {
                        Switch(
                            checked = hideSongStatus,
                            onCheckedChange = AppSettings::setHideSongStatus,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
                )
            }
        }
}
