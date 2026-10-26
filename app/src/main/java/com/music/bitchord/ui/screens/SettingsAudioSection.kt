package com.music.bitchord.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.*
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.download.*
import com.music.bitchord.playback.AudioOutputStatus
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsAudioSection(
    search: SettingsSearch,
    onSources: () -> Unit,
    onEqualizer: () -> Unit,
) {
    val context = LocalContext.current

    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixVideos by AppSettings.automixVideos.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val privateListening by AppSettings.privateListening.collectAsStateWithLifecycle()
    val dolbyAtmos by AppSettings.dolbyAtmos.collectAsStateWithLifecycle()
    // A property of the hardware, so it is read once rather than remembered
    // against a key that can never change — see [DeviceCodecs.playsDolbyAtmos],
    // which caches the codec-list walk for the life of the process.
    val dolbyAtmosSupported = DeviceCodecs.playsDolbyAtmos
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val resumeOnHeadphones by AppSettings.resumeOnHeadphones.collectAsStateWithLifecycle()
    val personalisedAutoplay by AppSettings.personalisedAutoplay.collectAsStateWithLifecycle()
    val sessionId by AppSettings.audioSessionId.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
    val playingDolbyAtmos = playingFormat?.isDolbyAtmos == true
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val autoRedownloadMvSound by AppSettings.autoRedownloadMvSound.collectAsStateWithLifecycle()
    val autoReplaceHqDownloads by AppSettings.autoReplaceHqDownloads.collectAsStateWithLifecycle()
    val autoPrefetchHq by AppSettings.autoPrefetchHq.collectAsStateWithLifecycle()
    val hqPrefetchLimit by AppSettings.hqPrefetchLimit.collectAsStateWithLifecycle()
    val autoDownloadLiked by AppSettings.autoDownloadLiked.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
    val widgetOpensPlayer by AppSettings.widgetOpensPlayer.collectAsStateWithLifecycle()
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val skipNonMusicParts by AppSettings.skipNonMusicParts.collectAsStateWithLifecycle()
    val skipSponsorParts by AppSettings.skipSponsorParts.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<QualityTarget?>(null) }
    var pickingDownloadQuality by remember { mutableStateOf(false) }
    var pickingAutomixPerformance by remember { mutableStateOf(false) }

        // The row that used to sit at the top of this group was called
        // "Lossless / HQ Audio" and toggled `SourceRegistry.setModuleEnabled` —
        // it switched the *module source* on and off, not lossless. Sources
        // above lists that as the module's own row now. Lossless itself is no
        // longer a setting at all — see
        // [SourceResolver.requestForNow][com.music.bitchord.data.sources.SourceResolver.requestForNow].
        SearchableSettingsGroup(search, header = stringResource(R.string.audio_quality)) {
            val sourceTitle = stringResource(R.string.source)
            val sourceSubtitle = stringResource(R.string.sources_subtitle)
            row(sourceTitle, sourceSubtitle, "addon", "lossless") {
                SettingsRow(
                    icon = Icons.Rounded.Extension,
                    title = sourceTitle,
                    subtitle = sourceSubtitle,
                    onClick = onSources,
                )
            }
            val onWifiTitle = stringResource(R.string.on_wifi)
            row(onWifiTitle, "wi-fi", "streaming quality") {
                SettingsRow(
                    icon = Icons.Rounded.Wifi,
                    title = onWifiTitle,
                    badge = stringResource(R.string.in_use).takeIf { metered == false },
                    value = wifiQuality.localizedLabel(),
                    onClick = { picking = QualityTarget.WIFI },
                )
            }
            val onMobileDataTitle = stringResource(R.string.on_mobile_data)
            row(onMobileDataTitle, "cellular", "streaming quality") {
                SettingsRow(
                    icon = Icons.Rounded.SignalCellularAlt,
                    title = onMobileDataTitle,
                    badge = stringResource(R.string.in_use).takeIf { metered == true },
                    value = cellularQuality.localizedLabel(),
                    onClick = { picking = QualityTarget.CELLULAR },
                )
            }
            // Sits with the quality ceilings rather than with Playback: it
            // decides which version of a track gets fetched, the same question
            // the two rows above answer, and not how one is played back.
            //
            // Greyed rather than hidden where the device can't decode E-AC-3.
            // A missing row reads as a feature the app doesn't have; a disabled
            // one with a reason under it is the difference between "BitChord
            // has no Atmos" and "this phone has no Dolby decoder", and only the
            // second is true. The stored preference is left untouched either
            // way — see [AppSettings.dolbyAtmos].
            val dolbyAtmosTitle = stringResource(R.string.dolby_atmos)
            row(dolbyAtmosTitle, "surround", "e-ac-3") {
                SettingsRow(
                    iconPainter = painterResource(R.drawable.ic_dolby_atmos),
                    title = dolbyAtmosTitle,
                    subtitle = stringResource(
                        if (dolbyAtmosSupported) {
                            R.string.dolby_atmos_subtitle
                        } else {
                            R.string.dolby_atmos_unavailable
                        },
                    ),
                    enabled = dolbyAtmosSupported,
                    trailing = {
                        Switch(
                            checked = dolbyAtmos && dolbyAtmosSupported,
                            onCheckedChange = AppSettings::setDolbyAtmos,
                            enabled = dolbyAtmosSupported,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setDolbyAtmos(!dolbyAtmos) },
                )
            }
        }

        // Its own group rather than rows bolted onto the two above, because a
        // download is not a third kind of connection. The ceilings answer "what
        // does this minute cost"; these answer "what am I keeping, and when may
        // it be fetched" — and those two questions only make sense read
        // together, which is what puts them side by side here.
        SearchableSettingsGroup(search, header = stringResource(R.string.downloads)) {
            val downloadQualityTitle = stringResource(R.string.download_quality)
            row(downloadQualityTitle, "offline", "lossless") {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = downloadQualityTitle,
                    subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                    value = downloadQuality.localizedLabel(),
                    onClick = { pickingDownloadQuality = true },
                )
            }
            // Reads as part of Download quality above it, not as a setting
            // of its own — same treatment as Play animated cover over
            // cellular gets under Animated cover art.
            val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
            row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
                SettingsSubRow(
                    title = downloadWifiOnlyTitle,
                    checked = wifiOnlyDownloads,
                    onCheckedChange = AppSettings::setWifiOnlyDownloads,
                    badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
                )
            }
            val autoRedownloadMvSoundTitle = stringResource(R.string.auto_redownload_mv_sound)
            row(autoRedownloadMvSoundTitle, "mv", "video", "download", "automatic", divided = false) {
                SettingsSubRow(
                    title = autoRedownloadMvSoundTitle,
                    checked = autoRedownloadMvSound,
                    onCheckedChange = { checked ->
                        AppSettings.setAutoRedownloadMvSound(checked)
                        if (checked) {
                            MvSoundRedownload.scheduleIfEnabled(context)
                        } else {
                            MvSoundRedownload.cancel(context)
                        }
                    },
                    subtitle = stringResource(R.string.auto_redownload_mv_sound_subtitle),
                )
            }
            val autoReplaceHqTitle = stringResource(R.string.auto_replace_hq_downloads)
            row(autoReplaceHqTitle, "hq", "high quality", "lossless", "replace", "고음질", divided = false) {
                SettingsSubRow(
                    title = autoReplaceHqTitle,
                    checked = autoReplaceHqDownloads,
                    onCheckedChange = { checked ->
                        AppSettings.setAutoReplaceHqDownloads(checked)
                        HqLibraryUpgrade.onSettingsChanged(context)
                    },
                    subtitle = stringResource(R.string.auto_replace_hq_downloads_subtitle),
                )
            }
            val autoPrefetchHqTitle = stringResource(R.string.auto_prefetch_hq)
            row(autoPrefetchHqTitle, "hq", "high quality", "prefetch", "offline", "고음질", divided = false) {
                SettingsSubRow(
                    title = autoPrefetchHqTitle,
                    checked = autoPrefetchHq,
                    onCheckedChange = { checked ->
                        AppSettings.setAutoPrefetchHq(checked)
                        HqLibraryUpgrade.onSettingsChanged(context)
                    },
                    subtitle = stringResource(R.string.auto_prefetch_hq_subtitle, hqPrefetchLimit),
                )
            }
            val recheckDownloadsTitle = stringResource(R.string.download_health_recheck)
            row(recheckDownloadsTitle, "download", "recheck", "verify", "다운로드", "점검", divided = false) {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = recheckDownloadsTitle,
                    subtitle = stringResource(R.string.download_health_recheck_subtitle),
                    trailing = { Chevron() },
                    onClick = { DownloadHealth.resetChecks(context) },
                )
            }
            val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
            row(exportDownloadsTitle, "music folder", divided = false) {
                SettingsSubRow(
                    title = exportDownloadsTitle,
                    checked = exportDownloads,
                    onCheckedChange = AppSettings::setExportDownloads,
                    subtitle = "Music/BitChord".takeIf { exportDownloads },
                )
            }
            val autoDownloadLikedTitle = stringResource(R.string.auto_download_liked)
            row(autoDownloadLikedTitle, "like", "heart", "offline", divided = false) {
                SettingsSubRow(
                    title = autoDownloadLikedTitle,
                    checked = autoDownloadLiked,
                    onCheckedChange = AppSettings::setAutoDownloadLiked,
                    subtitle = stringResource(R.string.auto_download_liked_subtitle),
                )
            }
        }

        SearchableSettingsGroup(search, header = stringResource(R.string.playback)) {
            val resumeOnHeadphonesTitle = stringResource(R.string.resume_on_headphones)
            row(resumeOnHeadphonesTitle, "headphones", "earbuds", "bluetooth", "resume", "이어폰") {
                SettingsRow(
                    icon = Icons.Rounded.Headphones,
                    title = resumeOnHeadphonesTitle,
                    subtitle = stringResource(R.string.resume_on_headphones_subtitle),
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
                    subtitle = stringResource(R.string.personalised_autoplay_subtitle),
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
            val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
            row(preferMusicOnlyTitle, "video", "audio", "music video") {
                SettingsRow(
                    icon = Icons.Rounded.SmartDisplay,
                    title = preferMusicOnlyTitle,
                    subtitle = stringResource(R.string.prefer_music_only_subtitle),
                    trailing = {
                        Switch(
                            checked = preferMusicOnly,
                            onCheckedChange = AppSettings::setPreferMusicOnly,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
                )
            }
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
            val outputPrecisionTitle = stringResource(R.string.output_precision)
            row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = outputPrecisionTitle,
                    subtitle = buildString {
                        append(outputStatus.sink)
                        append(" · ")
                        append(outputStatus.deviceName)
                        (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                            ?.let { append(" · ${it / 1000.0} kHz") }
                        append(" · ")
                        append(AudioOutputStatus.encodingLabel(outputStatus))
                    },
                )
                SettingsSegmentedControl(
                    options = OutputPcmMode.entries.map(OutputPcmMode::label),
                    selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                    onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                    modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
            row(preferUsbDacTitle, "headphone", "output") {
                SettingsSubRow(
                    title = preferUsbDacTitle,
                    checked = preferUsbDac,
                    onCheckedChange = AppSettings::setPreferUsbDac,
                    badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
                )
            }
            val loudnessTitle = stringResource(R.string.loudness_normalization)
            row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
                SettingsRow(
                    icon = Icons.Rounded.VolumeUp,
                    title = loudnessTitle,
                    subtitle = stringResource(R.string.loudness_normalization_subtitle),
                    trailing = {
                        Switch(
                            checked = loudnessNormalization,
                            onCheckedChange = AppSettings::setLoudnessNormalization,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
                )
            }
            val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
            row(loudnessSpeakerTitle, "loudness", "speaker", "normalize") {
                SettingsSubRow(
                    title = loudnessSpeakerTitle,
                    checked = loudnessOffOnSpeaker,
                    onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
                )
            }
            // Automix decides its own length from each pair of tracks —
            // tempo, key, structure — so it replaces the manual slider rather
            // than needing it set to anything first.
            if (!smartFade) {
                val crossfadeTitle = stringResource(R.string.crossfade)
                row(crossfadeTitle, "fade", "gapless") {
                    SliderRow(
                        icon = Icons.Rounded.Waves,
                        title = crossfadeTitle,
                        subtitle = stringResource(R.string.crossfade_subtitle),
                        value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                        sliderValue = crossfade.toFloat(),
                        onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                }
            }
            val automixTitle = stringResource(R.string.automix)
            row(automixTitle, "crossfade", "smart fade", "mix") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = automixTitle,
                    subtitle = if (smartFade) {
                        stringResource(R.string.automix_enabled_subtitle)
                    } else {
                        stringResource(R.string.automix_disabled_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = smartFade,
                            onCheckedChange = AppSettings::setSmartFadeEnabled,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
                )
            }
            if (smartFade) {
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
            val automixPerformanceTitle = stringResource(R.string.automix_performance)
            row(automixPerformanceTitle, "cpu", "battery") {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = automixPerformanceTitle,
                    subtitle = stringResource(R.string.automix_performance_subtitle),
                    value = automixPerformance.localizedLabel(),
                    onClick = { pickingAutomixPerformance = true },
                )
            }
            val skipSilenceTitle = stringResource(R.string.skip_silence)
            row(skipSilenceTitle, "silence") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    title = skipSilenceTitle,
                    // The low-level Media3 processor only runs on its 16-bit
                    // branch, so the precision sink now trims float and direct
                    // output itself before the DSP chain. The switch is live on
                    // both paths; only the implementation differs.
                    subtitle = stringResource(R.string.skip_silence_subtitle),
                    trailing = {
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = AppSettings::setSkipSilence,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSkipSilence(!skipSilence) },
                )
            }
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
            val spatialAudioTitle = stringResource(R.string.spatial_audio)
            row(spatialAudioTitle, "surround", "3d") {
                SettingsRow(
                    icon = Icons.Rounded.SurroundSound,
                    title = spatialAudioTitle,
                    // Widening a JOC stream would fight the object-based mix
                    // Dolby has already spatialized, so the service holds this
                    // off for the duration of an Atmos track — see
                    // `PlaybackService.applySpatialAudioEnabled`. Said on the
                    // row because the switch stays where the listener left it,
                    // and a switch reading "on" over an effect that is not
                    // running is the same silent lie the equaliser screen used
                    // to tell.
                    subtitle = if (playingDolbyAtmos) {
                        stringResource(R.string.spatial_audio_atmos_subtitle)
                    } else {
                        stringResource(R.string.spatial_audio_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = spatialAudio,
                            onCheckedChange = AppSettings::setSpatialAudio,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
                )
            }
            // The system panel is not listed here as well. A device with a
            // Dolby or Dirac panel has something BitChord cannot reproduce and
            // keeps its row — but one level in, at the foot of the equaliser
            // screen, rather than as a second equaliser entry alongside ours.
            val equalizerTitle = stringResource(R.string.equalizer)
            row(equalizerTitle, "eq", "bass", "treble") {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = equalizerTitle,
                    subtitle = stringResource(R.string.equalizer_subtitle),
                    onClick = onEqualizer,
                )
            }
        }

    picking?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { picking = null },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            QualitySheet(
                target = target,
                selected = when (target) {
                    QualityTarget.WIFI -> wifiQuality
                    QualityTarget.CELLULAR -> cellularQuality
                },
                // Writes the one ceiling that was being edited and nothing
                // else. There used to be a `SourceRegistry.applyQualityPreset`
                // call here that flipped the module and JioSaavn switches to
                // match — which meant budgeting *mobile data* switched those
                // sources off while sitting on Wi-Fi, and coming back to Wi-Fi
                // never switched them on again. Which sources a rung consults
                // is now read per stream off the connection in force; see
                // [AudioQuality.permits].
                onSelect = { quality ->
                    when (target) {
                        QualityTarget.WIFI -> AppSettings.setAudioQualityWifi(quality)
                        QualityTarget.CELLULAR -> AppSettings.setAudioQualityCellular(quality)
                    }
                    picking = null
                },
            )
        }
    }

    if (pickingDownloadQuality) {
        ModalBottomSheet(
            onDismissRequest = { pickingDownloadQuality = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            DownloadQualitySheet(
                selected = downloadQuality,
                onSelect = { quality ->
                    AppSettings.setDownloadQuality(quality)
                    pickingDownloadQuality = false
                },
            )
        }
    }

    if (pickingAutomixPerformance) {
        ModalBottomSheet(
            onDismissRequest = { pickingAutomixPerformance = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            AutomixPerformanceSheet(
                selected = automixPerformance,
                onSelect = { mode ->
                    AppSettings.setAutomixPerformanceMode(mode)
                    pickingAutomixPerformance = false
                },
            )
        }
    }

}

/** Which ceiling the open picker is editing. */
private enum class QualityTarget(val icon: ImageVector) {
    WIFI(Icons.Rounded.Wifi),
    CELLULAR(Icons.Rounded.SignalCellularAlt),
}

@Composable
private fun QualityTarget.localizedTitle(): String = stringResource(
    if (this == QualityTarget.WIFI) R.string.wifi else R.string.mobile_data,
)

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        // The top rung is "whatever the sources can do", which on YouTube is
        // its best Opus/AAC — not a lossless stream. The download picker keeps
        // "Lossless" because a saved file genuinely can be bit-exact.
        AudioQuality.LOSSLESS -> R.string.best_quality
    },
)

@Composable
private fun DownloadQuality.localizedLabel(): String = stringResource(
    when (this) {
        DownloadQuality.STANDARD -> R.string.standard
        DownloadQuality.HIGH -> R.string.high
        DownloadQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
private fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

/** The quality options for one connection, with what each costs in data. */
@Composable
private fun QualitySheet(
    target: QualityTarget,
    selected: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = target.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.audio_quality),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(
                        R.string.audio_quality_connection,
                        target.localizedTitle().lowercase(Locale.getDefault()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Best first — the option most people want shouldn't be last.
        AudioQuality.entries.reversed().forEach { quality ->
            val chosen = quality == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = quality.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.quality_hourly, quality.detail, quality.hourly),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** CPU budget picker for the background models that prepare Automix. */
@Composable
private fun AutomixPerformanceSheet(
    selected: AutomixPerformanceMode,
    onSelect: (AutomixPerformanceMode) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.automix_performance),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.automix_performance_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        AutomixPerformanceMode.entries.forEach { mode ->
            val chosen = mode == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(mode)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mode.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(
                            when (mode) {
                                AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                                AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                                AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * What to keep when a track is saved, with what each rung costs on disk.
 *
 * Priced per track rather than per hour, the way [QualitySheet] is. That sheet
 * is answering "what will listening cost me this hour", because a stream is
 * spent again on every replay; this one is answering "what will keeping this
 * cost me", and the answer is charged once. Same widget, different question, so
 * the numbers beside the options are in different units on purpose.
 */
@Composable
private fun DownloadQualitySheet(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.download_quality),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.download_quality_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Best first, matching [QualitySheet] — and here the best rung is also
        // the default, so the checkmark starts where the eye does.
        DownloadQuality.entries.reversed().forEach { quality ->
            val chosen = quality == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = quality.localizedLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.quality_per_track, quality.detail, quality.perTrack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
