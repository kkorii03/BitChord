package com.music.bitchord.ui.tv.screens
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TvSettings
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.tv.TvAppMark
import com.music.bitchord.ui.tv.audio.TvSpatialAudioEngine
import com.music.bitchord.ui.tv.components.TvChromePinned
import com.music.bitchord.ui.tv.components.TvListHeader
import com.music.bitchord.ui.tv.components.TvListRow
import com.music.bitchord.ui.tv.dialogs.TvAboutDialog
import com.music.bitchord.ui.tv.dialogs.TvEqualizerDialog
import com.music.bitchord.ui.tv.dialogs.TvLyricsSourcesDialog
import com.music.bitchord.ui.tv.dialogs.TvRefreshRateDialog
import com.music.bitchord.ui.tv.dialogs.TvScrobbleDialog
import com.music.bitchord.ui.tv.dialogs.TvThemeDialog
import com.music.bitchord.ui.tv.dialogs.TvUpdateDialog
import com.music.bitchord.ui.tv.display.TvRefreshRateController
import com.music.bitchord.ui.tv.personalization.AppThemeOption
import com.music.bitchord.ui.tv.theme.TvDimensions
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType

// Discord Rich Presence is hidden on TV for now; TvDiscordDialog is kept for when it returns.
private enum class SettingsSheet { Theme, RefreshRate, Equalizer, Scrobbling, LyricsSources, Update, About }

/**
 * Settings, as tvOS lays it out: the app on the left with a plain description of
 * whatever row has focus, the grouped rows on the right. Values are written out
 * ("On", "6 s") and change in place on select; rows that need a choice open one.
 */
@Composable
fun TvSettingsScreen(
    viewModel: MainViewModel,
    onOpenAccount: () -> Unit,
    onRunSetupAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvChromePinned()
    val signedIn by viewModel.signedIn.collectAsState()
    val account by viewModel.account.collectAsState()

    val automix by AppSettings.smartFadeEnabled.collectAsState()
    val crossfade by AppSettings.crossfadeSeconds.collectAsState()
    val autoplay by AppSettings.autoplay.collectAsState()
    val skipSilence by AppSettings.skipSilence.collectAsState()
    val eqEnabled by AppSettings.equalizerEnabled.collectAsState()
    val spatial by TvSettings.spatialAudioEnabled.collectAsState()
    val loudness by AppSettings.loudnessNormalization.collectAsState()
    val usbDac by AppSettings.preferUsbDac.collectAsState()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsState()
    val lyricsSources by AppSettings.lyricsSources.collectAsState()
    val canvas by AppSettings.animatedCanvas.collectAsState()
    val lyricsCanvas by TvSettings.tvLyricsCanvasEnabled.collectAsState()
    val nerdStats by AppSettings.showNerdStats.collectAsState()
    val themeId by TvSettings.tvTheme.collectAsState()
    val uiScale by TvSettings.tvUiScale.collectAsState()
    val refreshRate by TvRefreshRateController.preference.collectAsState()
    val reduceMotion by AppSettings.reduceAnimation.collectAsState()
    val reduceTransparency by AppSettings.reduceDynamicBlur.collectAsState()

    var blurbRes by remember { mutableStateOf(R.string.tv_default_blurb) }
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    val update by AppUpdateChecker.available.collectAsState()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(top = TvDimensions.ContentTop),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TvAppMark(size = 120.dp)
            Spacer(modifier = Modifier.height(22.dp))
            AnimatedContent(
                targetState = blurbRes,
                transitionSpec = { fadeIn().togetherWith(fadeOut()) },
                label = "settingsBlurb",
            ) { text ->
                Text(
                    text = stringResource(text),
                    style = TvType.Callout,
                    color = TvGlass.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 272.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(end = TvDimensions.SafeMarginHorizontal, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item { TvListHeader(stringResource(R.string.tv_account_header)) }
            item {
                TvListRow(
                    title = "YouTube Music",
                    value = if (signedIn) account?.name ?: stringResource(R.string.tv_signed_in) else stringResource(R.string.tv_sign_in),
                    onFocused = { blurbRes = R.string.tv_account_blurb },
                    onClick = onOpenAccount,
                )
            }

            item { TvListHeader(stringResource(R.string.tv_playback_header)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_automix),
                    value = onOff(automix),
                    onFocused = { blurbRes = R.string.tv_automix_blurb },
                    onClick = { AppSettings.setSmartFadeEnabled(!automix) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_crossfade),
                    value = if (crossfade > 0) stringResource(R.string.tv_seconds, crossfade) else stringResource(R.string.tv_off),
                    onFocused = { blurbRes = R.string.tv_crossfade_blurb },
                    onClick = {
                        AppSettings.setCrossfadeSeconds(
                            when (crossfade) {
                                0 -> 3
                                3 -> 6
                                6 -> 10
                                else -> 0
                            },
                        )
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_autoplay),
                    value = onOff(autoplay),
                    onFocused = { blurbRes = R.string.tv_autoplay_blurb },
                    onClick = { AppSettings.setAutoplay(!autoplay) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_skip_silence),
                    value = onOff(skipSilence),
                    onFocused = { blurbRes = R.string.tv_skip_silence_blurb },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }

            item { TvListHeader(stringResource(R.string.tv_sound_header)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_equalizer),
                    value = onOff(eqEnabled),
                    onFocused = { blurbRes = R.string.tv_equalizer_blurb },
                    onClick = { sheet = SettingsSheet.Equalizer },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_spatial_audio),
                    value = onOff(spatial),
                    onFocused = { blurbRes = R.string.tv_spatial_audio_blurb },
                    onClick = {
                        TvSettings.setSpatialAudioEnabled(!spatial)
                        TvSpatialAudioEngine.setEnabled(!spatial)
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_sound_check),
                    value = onOff(loudness),
                    onFocused = { blurbRes = R.string.tv_sound_check_blurb },
                    onClick = { AppSettings.setLoudnessNormalization(!loudness) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_usb_dac),
                    value = onOff(usbDac),
                    onFocused = { blurbRes = R.string.tv_usb_dac_blurb },
                    onClick = { AppSettings.setPreferUsbDac(!usbDac) },
                )
            }

            item { TvListHeader(stringResource(R.string.tv_now_playing_header)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_synced_lyrics),
                    value = onOff(syncedLyrics),
                    onFocused = { blurbRes = R.string.tv_synced_lyrics_blurb },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_lyrics_sources),
                    value = stringResource(R.string.tv_sources_on, lyricsSources.size),
                    onFocused = { blurbRes = R.string.tv_lyrics_sources_blurb },
                    onClick = { sheet = SettingsSheet.LyricsSources },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_motion_artwork),
                    value = onOff(canvas),
                    onFocused = { blurbRes = R.string.tv_motion_artwork_blurb },
                    onClick = { AppSettings.setAnimatedCanvas(!canvas) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_motion_artwork_lyrics),
                    value = onOff(lyricsCanvas),
                    onFocused = { blurbRes = R.string.tv_motion_artwork_lyrics_blurb },
                    onClick = { TvSettings.setTvLyricsCanvasEnabled(!lyricsCanvas) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_stats_for_nerds),
                    value = onOff(nerdStats),
                    onFocused = { blurbRes = R.string.tv_stats_for_nerds_blurb },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }

            item { TvListHeader(stringResource(R.string.tv_display_header)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_appearance),
                    value = stringResource(AppThemeOption.fromId(themeId).titleRes),
                    onFocused = { blurbRes = R.string.tv_appearance_blurb },
                    onClick = { sheet = SettingsSheet.Theme },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_interface_size),
                    value = "${(uiScale * 100).toInt()}%",
                    onFocused = { blurbRes = R.string.tv_interface_size_blurb },
                    onClick = {
                        val steps = listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f)
                        val next = steps.firstOrNull { it > uiScale + 0.01f } ?: steps.first()
                        TvSettings.setTvUiScale(next)
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_refresh_rate),
                    value = stringResource(refreshRate.labelRes),
                    onFocused = { blurbRes = R.string.tv_refresh_rate_blurb },
                    onClick = { sheet = SettingsSheet.RefreshRate },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_reduce_motion),
                    value = onOff(reduceMotion),
                    onFocused = { blurbRes = R.string.tv_reduce_motion_blurb },
                    onClick = { AppSettings.setReduceAnimation(!reduceMotion) },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_reduce_transparency),
                    value = onOff(reduceTransparency),
                    onFocused = { blurbRes = R.string.tv_reduce_transparency_blurb },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceTransparency) },
                )
            }

            item { TvListHeader(stringResource(R.string.tv_connections_header)) }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_scrobbling),
                    onFocused = { blurbRes = R.string.tv_scrobbling_blurb },
                    onClick = { sheet = SettingsSheet.Scrobbling },
                )
            }

            item { TvListHeader(stringResource(R.string.tv_general_header)) }
            if (update != null) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_software_update),
                        value = stringResource(R.string.tv_available_suffix, update?.version.orEmpty()),
                        onFocused = { blurbRes = R.string.tv_software_update_blurb },
                        onClick = { sheet = SettingsSheet.Update },
                    )
                }
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_set_up_again),
                    onFocused = { blurbRes = R.string.tv_set_up_again_blurb },
                    onClick = onRunSetupAgain,
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_about),
                    value = BuildConfig.VERSION_NAME,
                    onFocused = { blurbRes = R.string.tv_default_blurb },
                    onClick = { sheet = SettingsSheet.About },
                )
            }
        }
    }

    when (sheet) {
        SettingsSheet.Theme -> TvThemeDialog(onDismiss = { sheet = null })
        SettingsSheet.RefreshRate -> TvRefreshRateDialog(onDismiss = { sheet = null })
        SettingsSheet.Equalizer -> TvEqualizerDialog(onDismiss = { sheet = null })
        SettingsSheet.Scrobbling -> TvScrobbleDialog(onDismiss = { sheet = null })
        SettingsSheet.LyricsSources -> TvLyricsSourcesDialog(onDismiss = { sheet = null })
        SettingsSheet.Update -> update?.let { TvUpdateDialog(update = it, onDismiss = { sheet = null }) }
        SettingsSheet.About -> TvAboutDialog(onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun onOff(value: Boolean) = stringResource(if (value) R.string.tv_on else R.string.tv_off)
