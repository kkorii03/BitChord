package com.music.bitchord.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale

/**
 * The rows [LyricsSettingsScreen] carries, in the order the old "가사" group
 * had them. Kept as data so the main Settings search and its test can name
 * every one without walking the composition.
 */
internal object LyricsSettingsCatalog {
    val keys = listOf(
        "synced_lyrics",
        "lyrics_blur",
        "lyrics_sources",
        "translation_language",
        "gemini_translation",
        "auto_translate_lyrics",
        "auto_lyrics_timing",
        "ai_timing_ahead",
        "reset_download_sync",
        "deepgram_api_key",
        "ai_lyrics",
        "prefetch_translation",
        "keep_download_translation",
        "lock_screen_lyrics",
    )
}

/**
 * Every lyrics setting, moved off the main Settings list as a page of its own.
 *
 * The rows keep the order and the behaviour they had inside the old "가사"
 * group; only their home changed. Settings keys, defaults and the search
 * keywords live in the screens that own them.
 */
@Composable
fun LyricsSettingsScreen(
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onGeminiKey: () -> Unit,
    onDeepgramKey: () -> Unit,
    onResetDownloadSync: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.settings_lyrics),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        SettingsGroup(topSpacing = 0.dp) {
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art on the main page: the lyrics
            // source and translation rows are only here while this is on.
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
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                RowDivider()
                val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
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
                RowDivider()
                val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
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
                RowDivider()
                val translationLanguageTitle = stringResource(R.string.translation_language)
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
                RowDivider()
                val geminiTranslationTitle = stringResource(R.string.gemini_translation)
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
                RowDivider()
                val autoTranslateTitle = stringResource(R.string.auto_translate_lyrics)
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
                RowDivider()
                val autoLyricsTimingTitle = stringResource(R.string.auto_lyrics_timing)
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
                RowDivider()
                val aiTimingAheadTitle = stringResource(R.string.ai_timing_ahead)
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
                RowDivider()
                val resetDownloadSyncTitle = stringResource(R.string.reset_download_sync)
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = resetDownloadSyncTitle,
                    subtitle = stringResource(R.string.reset_download_sync_subtitle),
                    trailing = { Chevron() },
                    onClick = onResetDownloadSync,
                )
                RowDivider()
                val deepgramTitle = stringResource(R.string.deepgram_api_key)
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
                RowDivider()
                val aiLyricsTitle = stringResource(R.string.ai_lyrics)
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
                RowDivider()
                val prefetchTranslationTitle = stringResource(R.string.prefetch_translation)
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
                RowDivider()
                val keepDownloadTranslationTitle = stringResource(R.string.keep_download_translation)
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
                RowDivider()
                val lockScreenLyricsTitle = stringResource(R.string.lock_screen_lyrics)
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
    }
}
