package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.*
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import java.util.Locale

@Composable
internal fun SettingsAppearanceSection(
    search: SettingsSearch,
    windowWidth: Dp,
    onSpotifyCanvasAuth: () -> Unit,
    onEditSignatureApi: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onGeminiKey: () -> Unit,
    onDeepgramKey: () -> Unit,
    onResetDownloadSync: () -> Unit,
) {
    val context = LocalContext.current

    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val lowPowerMode by AppSettings.lowPowerMode.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val videoCanvas by AppSettings.videoCanvas.collectAsStateWithLifecycle()
    val signatureApiEnabled by AppSettings.signatureApiEnabled.collectAsStateWithLifecycle()
    val signatureApiUrl by AppSettings.signatureApiUrl.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val geminiTranslation by AppSettings.geminiTranslation.collectAsStateWithLifecycle()
    val geminiApiKey by AppSettings.geminiApiKey.collectAsStateWithLifecycle()
    val deepgramApiKey by AppSettings.deepgramApiKey.collectAsStateWithLifecycle()
    val deepgramKeyRejected by com.music.bitchord.data.lyrics.Deepgram.keyRejected.collectAsStateWithLifecycle()
    val prefetchTranslation by AppSettings.prefetchTranslation.collectAsStateWithLifecycle()
    val autoTranslateLyrics by AppSettings.autoTranslateLyrics.collectAsStateWithLifecycle()
    val autoLyricsTiming by AppSettings.autoLyricsTiming.collectAsStateWithLifecycle()
    val aiLyrics by AppSettings.aiLyrics.collectAsStateWithLifecycle()
    val aiTimingAhead by AppSettings.aiTimingAhead.collectAsStateWithLifecycle()
    val syncSafetyNextPrep by AppSettings.syncSafetyNextPrep.collectAsStateWithLifecycle()
    val syncSafetyDownloadRecheck by AppSettings.syncSafetyDownloadRecheck.collectAsStateWithLifecycle()
    val syncSafetyHqSync by AppSettings.syncSafetyHqSync.collectAsStateWithLifecycle()
    val keepDownloadTranslation by AppSettings.keepDownloadTranslation.collectAsStateWithLifecycle()
    val lockScreenLyrics by AppSettings.lockScreenLyrics.collectAsStateWithLifecycle()
    // Read again whenever the page comes back, which is how a grant made in
    // the system's own screen shows up here.
    val settingsForeground = com.music.bitchord.ui.rememberIsForeground()
    val canDrawOverlays = remember(settingsForeground) {
        com.music.bitchord.lockscreen.LockScreenLyrics.canShow(context)
    }
    val openOverlayPermission: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val geminiKeyRejected by com.music.bitchord.data.lyrics.LyricsTranslation.geminiKeyRejected
        .collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val hiddenExploreCategories by AppSettings.hiddenExploreCategories.collectAsStateWithLifecycle()
    var showHiddenCategories by remember { mutableStateOf(false) }

        SearchableSettingsGroup(search, header = stringResource(R.string.appearance)) {
            val themeTitle = stringResource(R.string.theme)
            row(themeTitle, "dark mode", "light mode") {
                SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                SettingsSegmentedControl(
                    options = ThemeMode.entries.map { it.localizedLabel() },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val reduceAnimationTitle = stringResource(R.string.reduce_animation)
            row(reduceAnimationTitle, "motion") {
                SettingsRow(
                    icon = Icons.Rounded.MotionPhotosOff,
                    title = reduceAnimationTitle,
                    subtitle = stringResource(R.string.reduce_animation_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceAnimation,
                            onCheckedChange = AppSettings::setReduceAnimation,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                )
            }
            val lowPowerTitle = stringResource(R.string.low_power_mode)
            row(lowPowerTitle, "battery", "heat", "power saving") {
                SettingsRow(
                    icon = Icons.Rounded.BatterySaver,
                    title = lowPowerTitle,
                    subtitle = stringResource(R.string.low_power_mode_subtitle),
                )
                SettingsSegmentedControl(
                    options = LowPowerMode.entries.map { it.localizedLabel() },
                    selectedIndex = LowPowerMode.entries.indexOf(lowPowerMode),
                    onSelect = { AppSettings.setLowPowerMode(LowPowerMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
            row(reduceDynamicBlurTitle, "blur", "performance") {
                SettingsRow(
                    icon = Icons.Rounded.BlurOff,
                    title = reduceDynamicBlurTitle,
                    subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceDynamicBlur,
                            onCheckedChange = AppSettings::setReduceDynamicBlur,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                )
            }
            val liquidGlassTitle = stringResource(R.string.liquid_glass)
            row(liquidGlassTitle, "glass", "blur") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = liquidGlassTitle,
                    subtitle = stringResource(
                        if (liquidGlassSupported) {
                            R.string.liquid_glass_subtitle
                        } else {
                            R.string.liquid_glass_unavailable
                        },
                    ),
                    enabled = liquidGlassSupported,
                    trailing = {
                        Switch(
                            checked = liquidGlass,
                            onCheckedChange = AppSettings::setLiquidGlass,
                            enabled = liquidGlassSupported,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                )
            }
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                row(fullScreenCoverArtTitle, "artwork", "player") {
                    SettingsRow(
                        icon = Icons.Rounded.Fullscreen,
                        title = fullScreenCoverArtTitle,
                        subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                        trailing = {
                            Switch(
                                checked = fullBleedArtwork,
                                onCheckedChange = AppSettings::setFullBleedArtwork,
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                    )
                }
            }
            val legacyMeshGradientTitle = stringResource(R.string.legacy_mesh_gradient)
            row(legacyMeshGradientTitle, "background", "player") {
                SettingsRow(
                    icon = Icons.Rounded.Gradient,
                    title = legacyMeshGradientTitle,
                    subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                    trailing = {
                        Switch(
                            checked = legacyMeshGradient,
                            onCheckedChange = AppSettings::setLegacyMeshGradient,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
                )
            }
            val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
            // Carries the keywords of the rows that only appear once it is on,
            // so searching "spotify" with animated covers switched off turns up
            // the switch that brings the Spotify row back rather than nothing.
            row(animatedCoverArtTitle, "canvas", "video", "spotify") {
                SettingsRow(
                    icon = Icons.Rounded.Animation,
                    title = animatedCoverArtTitle,
                    subtitle = stringResource(R.string.animated_cover_art_subtitle),
                    trailing = {
                        Switch(
                            checked = animatedCanvas,
                            onCheckedChange = AppSettings::setAnimatedCanvas,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
                )
            }
            // Reads as part of the Animated cover art option above it, not
            // as a separate setting. Nothing to narrow while the clip itself
            // is off. Defaults to off: a clip loops for as long as its track
            // plays, so on cellular this is not a one-time video cost but
            // that cost repeated on every loop — see AppSettings.canvasOverCellular.
            if (animatedCanvas) {
                val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
                row(coverCellularTitle, "canvas", "cellular", divided = false) {
                    SettingsSubRow(
                        title = coverCellularTitle,
                        checked = canvasOverCellular,
                        onCheckedChange = AppSettings::setCanvasOverCellular,
                    )
                }
                val videoCanvasTitle = stringResource(R.string.video_canvas)
                row(videoCanvasTitle, "canvas", "video", "music video", divided = false) {
                    SettingsSubRow(
                        title = videoCanvasTitle,
                        checked = videoCanvas,
                        onCheckedChange = AppSettings::setVideoCanvas,
                    )
                }
                val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
                row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSpotifyCanvasAuth)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spotifyCanvasTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
            val artistSignaturesTitle = stringResource(R.string.artist_signatures)
            row(artistSignaturesTitle, "signature", "artist", "logo") {
                SettingsRow(
                    icon = Icons.Rounded.Draw,
                    title = artistSignaturesTitle,
                    subtitle = stringResource(R.string.artist_signatures_subtitle),
                    trailing = {
                        Switch(
                            checked = signatureApiEnabled,
                            onCheckedChange = AppSettings::setSignatureApiEnabled,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSignatureApiEnabled(!signatureApiEnabled) },
                )
            }
            if (signatureApiEnabled) {
                val signatureUrlTitle = stringResource(R.string.artist_signatures_url)
                row(signatureUrlTitle, "signature", "api", "url", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onEditSignatureApi)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = signatureApiUrl.ifBlank {
                                stringResource(R.string.artist_signatures_unconfigured)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art above: the lyrics source and
            // translation rows are only here while this is on.
            row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = syncedLyricsTitle,
                    subtitle = stringResource(R.string.synced_lyrics_subtitle),
                    trailing = {
                        Switch(
                            checked = syncedLyrics,
                            onCheckedChange = AppSettings::setSyncedLyrics,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
                row(lyricsBlurTitle, "lyrics", "blur") {
                    SettingsRow(
                        icon = Icons.Rounded.BlurOn,
                        title = lyricsBlurTitle,
                        subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = lyricsBlur,
                                onCheckedChange = AppSettings::setLyricsBlur,
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                    )
                }
                val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
                row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                    SettingsRow(
                        icon = Icons.Rounded.Language,
                        title = lyricsSourcesTitle,
                        subtitle = lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                            .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                        trailing = { Chevron() },
                        onClick = onLyricsSources,
                    )
                }
                val translationLanguageTitle = stringResource(R.string.translation_language)
                row(translationLanguageTitle, "lyrics", "translate") {
                    SettingsRow(
                        icon = Icons.Rounded.Translate,
                        title = translationLanguageTitle,
                        subtitle = if (translationLanguage.isBlank()) {
                            stringResource(R.string.translation_language_subtitle)
                        } else {
                            translationLanguageName(
                                translationLanguage,
                                AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                            )
                        },
                        trailing = { Chevron() },
                        onClick = onTranslationLanguage,
                    )
                }
                val geminiTranslationTitle = stringResource(R.string.gemini_translation)
                row(geminiTranslationTitle, "lyrics", "translate", "gemini", "ai") {
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = geminiTranslationTitle,
                        subtitle = stringResource(
                            when {
                                geminiApiKey.isBlank() -> R.string.gemini_translation_subtitle_missing
                                geminiKeyRejected -> R.string.gemini_translation_subtitle_rejected
                                else -> R.string.gemini_translation_subtitle_ready
                            },
                        ),
                        trailing = {
                            Switch(
                                checked = geminiTranslation && geminiApiKey.isNotBlank(),
                                onCheckedChange = { on ->
                                    if (on && geminiApiKey.isBlank()) onGeminiKey()
                                    else AppSettings.setGeminiTranslation(on)
                                },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = onGeminiKey,
                    )
                }
                val autoTranslateTitle = stringResource(R.string.auto_translate_lyrics)
                row(autoTranslateTitle, "lyrics", "translate", "auto") {
                    SettingsRow(
                        icon = Icons.Rounded.Translate,
                        title = autoTranslateTitle,
                        subtitle = stringResource(R.string.auto_translate_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = autoTranslateLyrics,
                                onCheckedChange = { AppSettings.setAutoTranslateLyrics(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAutoTranslateLyrics(!autoTranslateLyrics) },
                    )
                }
                val autoLyricsTimingTitle = stringResource(R.string.auto_lyrics_timing)
                row(autoLyricsTimingTitle, "lyrics", "timing", "sync", "video", "ai", "gemini") {
                    SettingsRow(
                        icon = Icons.Rounded.Timer,
                        title = autoLyricsTimingTitle,
                        subtitle = stringResource(R.string.auto_lyrics_timing_subtitle),
                        trailing = {
                            Switch(
                                checked = autoLyricsTiming,
                                onCheckedChange = { AppSettings.setAutoLyricsTiming(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAutoLyricsTiming(!autoLyricsTiming) },
                    )
                }
                val aiTimingAheadTitle = stringResource(R.string.ai_timing_ahead)
                row(aiTimingAheadTitle, "lyrics", "timing", "sync", "ai", "gemini", "ahead") {
                    SettingsRow(
                        icon = Icons.Rounded.Timer,
                        title = aiTimingAheadTitle,
                        subtitle = stringResource(R.string.ai_timing_ahead_subtitle),
                        trailing = {
                            Switch(
                                checked = aiTimingAhead,
                                onCheckedChange = { AppSettings.setAiTimingAhead(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAiTimingAhead(!aiTimingAhead) },
                    )
                }
                val resetDownloadSyncTitle = stringResource(R.string.reset_download_sync)
                row(resetDownloadSyncTitle, "lyrics", "timing", "sync", "download", "recheck") {
                    SettingsRow(
                        icon = Icons.Rounded.Cached,
                        title = resetDownloadSyncTitle,
                        subtitle = stringResource(R.string.reset_download_sync_subtitle),
                        trailing = { Chevron() },
                        onClick = onResetDownloadSync,
                    )
                }
                val deepgramTitle = stringResource(R.string.deepgram_api_key)
                row(deepgramTitle, "lyrics", "timing", "sync", "ai", "deepgram", "whisper", "key") {
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = deepgramTitle,
                        subtitle = stringResource(
                            when {
                                deepgramApiKey.isBlank() -> R.string.deepgram_subtitle_missing
                                deepgramKeyRejected -> R.string.deepgram_subtitle_rejected
                                AppSettings.deepgramKeyIsBundled -> R.string.deepgram_subtitle_bundled
                                else -> R.string.deepgram_subtitle_ready
                            },
                        ) + if (deepgramApiKey.length >= 8) " (••••${deepgramApiKey.takeLast(4)})" else "",
                        trailing = { Chevron() },
                        onClick = onDeepgramKey,
                    )
                }
                val aiLyricsTitle = stringResource(R.string.ai_lyrics)
                row(aiLyricsTitle, "lyrics", "ai", "gemini", "transcribe") {
                    SettingsRow(
                        icon = Icons.Rounded.Timer,
                        title = aiLyricsTitle,
                        subtitle = stringResource(R.string.ai_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = aiLyrics,
                                onCheckedChange = { AppSettings.setAiLyrics(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setAiLyrics(!aiLyrics) },
                    )
                }
                val prefetchTranslationTitle = stringResource(R.string.prefetch_translation)
                row(prefetchTranslationTitle, "lyrics", "translate", "next", "prefetch") {
                    SettingsRow(
                        icon = Icons.Rounded.Cached,
                        title = prefetchTranslationTitle,
                        subtitle = stringResource(R.string.prefetch_translation_subtitle),
                        trailing = {
                            Switch(
                                checked = prefetchTranslation,
                                onCheckedChange = { AppSettings.setPrefetchTranslation(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setPrefetchTranslation(!prefetchTranslation) },
                    )
                }
                val keepDownloadTranslationTitle = stringResource(R.string.keep_download_translation)
                row(keepDownloadTranslationTitle, "lyrics", "translate", "download", "offline") {
                    SettingsRow(
                        icon = Icons.Rounded.Download,
                        title = keepDownloadTranslationTitle,
                        subtitle = stringResource(R.string.keep_download_translation_subtitle),
                        trailing = {
                            Switch(
                                checked = keepDownloadTranslation,
                                onCheckedChange = { AppSettings.setKeepDownloadTranslation(it) },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = { AppSettings.setKeepDownloadTranslation(!keepDownloadTranslation) },
                    )
                }
                val lockScreenLyricsTitle = stringResource(R.string.lock_screen_lyrics)
                row(lockScreenLyricsTitle, "lyrics", "lock screen", "lockscreen") {
                    SettingsRow(
                        icon = Icons.Rounded.Lock,
                        title = lockScreenLyricsTitle,
                        subtitle = stringResource(
                            if (lockScreenLyrics && !canDrawOverlays) {
                                R.string.lock_screen_lyrics_needs_permission
                            } else {
                                R.string.lock_screen_lyrics_subtitle
                            },
                        ),
                        trailing = {
                            Switch(
                                checked = lockScreenLyrics && canDrawOverlays,
                                onCheckedChange = { on ->
                                    AppSettings.setLockScreenLyrics(on)
                                    if (on && !canDrawOverlays) openOverlayPermission()
                                },
                                colors = settingsSwitchColors(),
                            )
                        },
                        onClick = {
                            if (lockScreenLyrics && !canDrawOverlays) {
                                openOverlayPermission()
                            } else {
                                val on = !lockScreenLyrics
                                AppSettings.setLockScreenLyrics(on)
                                if (on && !canDrawOverlays) openOverlayPermission()
                            }
                        },
                    )
                }
            }
            val hiddenCategoriesTitle = stringResource(R.string.hidden_explore_categories)
            row(hiddenCategoriesTitle, "explore", "category", "filter", "hide") {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = hiddenCategoriesTitle,
                    subtitle = if (hiddenExploreCategories.isEmpty()) {
                        stringResource(R.string.hidden_explore_categories_none)
                    } else {
                        stringResource(
                            R.string.hidden_explore_categories_count,
                            hiddenExploreCategories.size,
                        )
                    },
                    trailing = { Chevron() },
                    onClick = { showHiddenCategories = true },
                )
            }
        }

    if (showHiddenCategories) {
        AlertDialog(
            onDismissRequest = { showHiddenCategories = false },
            title = { Text(stringResource(R.string.hidden_explore_categories)) },
            text = {
                if (hiddenExploreCategories.isEmpty()) {
                    Text(stringResource(R.string.hidden_explore_categories_none))
                } else {
                    Column {
                        hiddenExploreCategories.sorted().forEach { title ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { AppSettings.showExploreCategory(title) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.show_again),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { AppSettings.clearHiddenExploreCategories() },
                    enabled = hiddenExploreCategories.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.show_all_again))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHiddenCategories = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)

@Composable
private fun LowPowerMode.localizedLabel(): String = stringResource(
    when (this) {
        LowPowerMode.AUTO -> R.string.low_power_auto
        LowPowerMode.ON -> R.string.low_power_on
        LowPowerMode.OFF -> R.string.low_power_off
    },
)
