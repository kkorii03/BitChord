package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.cover.videoThumbnailUrl
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import com.music.bitchord.data.remote.DownloadedArtworkResolver
import com.music.bitchord.data.remote.RemoteArtworkStore

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the remote file and cached — else null.
 *
 * A downloaded YouTube track is resolved through its own ladder — embedded
 * file picture, saved sidecar, YouTube still — because its recorded thumbnail
 * may be an album-art URI that no longer resolves. The extraction only ever
 * runs once per track; every other row resolves synchronously to what it
 * already had, so this is safe to call from shared components like [SongRow]
 * that draw every library in the app. Keyed on the track id, so a recycled row
 * re-resolves instead of flashing a stranger's cover.
 */
@Composable
fun rememberRemoteArtworkUrl(song: Song?): String? {
    val context = LocalContext.current
    val key = song?.let {
        listOf(it.videoId, it.localUri.orEmpty(), it.thumbnailUrl.orEmpty()).joinToString("\u0001")
    }
    // A YouTube row with no artwork reached the player as a black square;
    // the video still is the last resort (and local files never get one).
    val still = song?.takeIf { !SongOrigins.isLocalFile(it) }?.videoId?.let(::videoThumbnailUrl)
    val downloaded = song != null && SongOrigins.isDownloadedYouTube(song)
    var resolved by remember(key) {
        mutableStateOf(
            when {
                song == null || downloaded -> null
                else -> song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: still
            },
        )
    }
    LaunchedEffect(key) {
        if (song == null) return@LaunchedEffect
        resolved = when {
            downloaded -> DownloadedArtworkResolver.resolve(context, song)
            song.thumbnailUrl == null &&
                (song.videoId.startsWith("webdav:") || song.videoId.startsWith("smb:")) ->
                RemoteArtworkStore.resolveArt(context, song)
            else -> song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: still
        }
    }
    return resolved
}
