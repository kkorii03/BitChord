package com.music.bitchord.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.wear.WatchInstaller
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun SettingsLibrarySection(
    search: SettingsSearch,
    onWearOs: () -> Unit,
) {
    val context = LocalContext.current

    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    var confirmClearSongCache by remember { mutableStateOf(false) }
    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

        SearchableSettingsGroup(search, header = stringResource(R.string.local_music)) {
            val localMusicFolderTitle = stringResource(R.string.local_music_folder)
            row(localMusicFolderTitle, "folder", "offline", "files") {
                SettingsRow(
                    icon = Icons.Rounded.Folder,
                    title = localMusicFolderTitle,
                    subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                        ?: stringResource(R.string.all_audio_folders),
                    onClick = { localMusicFolderPicker.launch(null) },
                )
            }
            if (localMusicFolderUri.isNotBlank()) {
                val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
                row(useAllAudioFoldersTitle, "folder") {
                    SettingsRow(
                        icon = Icons.Rounded.LibraryMusic,
                        title = useAllAudioFoldersTitle,
                        subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                        onClick = { AppSettings.setLocalMusicFolderUri("") },
                    )
                }
            }
            val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
            row(filterNonMusicAudioTitle, "podcast", "recording") {
                SettingsRow(
                    icon = Icons.Rounded.FilterAlt,
                    title = filterNonMusicAudioTitle,
                    subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                    trailing = {
                        Switch(
                            checked = filterNonMusicAudio,
                            onCheckedChange = AppSettings::setFilterNonMusicAudio,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
                )
            }
        }

        val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
        // The slider's last stop, one step past the largest fixed size, is
        // "Unlimited" — see [AppSettings.setAudioCacheLimitBytes].
        val cacheLimitMb = if (cacheUnlimited) CACHE_UNLIMITED_STOP_MB else (cacheLimitBytes / (1024 * 1024)).toInt()
        val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
        val cacheLimitLabel = if (cacheUnlimited) unlimitedLabel else formatCacheSize(cacheLimitMb)
        SearchableSettingsGroup(search, header = stringResource(R.string.storage)) {
            val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
            row(showCacheFolderTitle, "cache", "cached songs", "library", "folder") {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = showCacheFolderTitle,
                    subtitle = stringResource(R.string.show_cache_folder_subtitle),
                    trailing = {
                        Switch(
                            checked = showCacheFolder,
                            onCheckedChange = AppSettings::setShowCacheFolder,
                            colors = settingsSwitchColors(),
                        )
                    },
                    onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
                )
            }
            val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
            row(songCacheLimitTitle, "cache", "space", "unlimited") {
                SliderRow(
                    icon = Icons.Rounded.Storage,
                    title = songCacheLimitTitle,
                    subtitle = when {
                        cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                        cacheLimitMb > CACHE_WARNING_MB ->
                            stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                        else -> stringResource(R.string.song_cache_limit_subtitle)
                    },
                    value = cacheLimitLabel,
                    sliderValue = cacheLimitMb.toFloat(),
                    onSliderValue = {
                        AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                    },
                    valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                        CACHE_UNLIMITED_STOP_MB.toFloat(),
                    steps = 19,
                )
            }
            val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
            row(clearSongCacheTitle, "cache", "free space") {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearSongCacheTitle,
                    subtitle = stringResource(R.string.clear_song_cache_subtitle),
                    onClick = { confirmClearSongCache = true },
                )
            }
            val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
            row(clearImageCacheTitle, "cache", "artwork", "free space") {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearImageCacheTitle,
                    subtitle = stringResource(R.string.clear_image_cache_subtitle),
                    onClick = {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }

        // Only in a build that carries the watch app — see app/build.gradle.kts.
        if (remember { WatchInstaller.available(context) }) {
            SearchableSettingsGroup(search) {
                row(stringResource(R.string.wear_os_section), "watch", "wear os", "galaxy watch", "pixel watch", "install") {
                    WearOsSettingsRow(onClick = onWearOs)
                }
            }
        }

    // Everything in the Cached songs folder goes with it, so say so first.
    if (confirmClearSongCache) {
        AlertDialog(
            onDismissRequest = { confirmClearSongCache = false },
            title = { Text(stringResource(R.string.clear_song_cache_title)) },
            text = { Text(stringResource(R.string.clear_song_cache_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearSongCache = false
                    AudioCache.clear {
                        Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSongCache = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

}

/** Above this, the cache limit slider's subtitle warns rather than reassures. */
private const val CACHE_WARNING_MB = 2048

/**
 * Where the cache limit slider's "Unlimited" stop sits: one 512MB step past
 * [AppSettings.MAX_CACHE_LIMIT_BYTES], so the fixed sizes keep their positions.
 */
private const val CACHE_UNLIMITED_STOP_MB = 10 * 1024 + 512

/** "512 MB", "2 GB", "2.5 GB" — whichever reads more naturally at that size. */
private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}
