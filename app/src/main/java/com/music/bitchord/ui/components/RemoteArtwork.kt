package com.music.bitchord.ui.components

import android.net.Uri
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
import com.music.bitchord.data.remote.DownloadedArtworkEvents
import com.music.bitchord.data.remote.DownloadedArtworkResolver
import com.music.bitchord.data.remote.RemoteArtworkStore
import java.io.File
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * A drawable art URL for [song]: its own thumbnail when it has one, else the
 * embedded picture pulled out of the remote file and cached — else null.
 *
 * A downloaded YouTube track is resolved through its own ladder — embedded
 * file picture, saved sidecar, YouTube still — because its recorded thumbnail
 * may be an album-art URI that no longer resolves. The extraction runs once per
 * track and again only when that track's cover-save event arrives; every other
 * row resolves synchronously to what it already had, so this is safe to call
 * from shared components like [SongRow] that draw every library in the app.
 * Keyed on the track id, so a recycled row re-resolves instead of flashing a
 * stranger's cover.
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
        if (downloaded) {
            // Subscribe before the first read, so a cover that lands while the
            // read is running is not missed. The save itself retires the
            // remembered answer (see [DownloadedArtworkEvents]); this row only
            // asks again. Nothing polls the disk.
            launch {
                DownloadedArtworkEvents.saved
                    .filter { it == song.videoId }
                    .collect {
                        resolved = versionedFileArtwork(
                            DownloadedArtworkResolver.resolve(context, song) ?: still,
                        )
                    }
            }
        }
        resolved = when {
            // A downloaded cover ladder can still end in "no picture" (a video
            // file with no embedded art and a sidecar that was deleted). The
            // YouTube still is the same last rung the player and notification
            // already use, so a video song never falls through to a blank tile.
            downloaded -> versionedFileArtwork(
                DownloadedArtworkResolver.resolve(context, song) ?: still,
            )
            song.thumbnailUrl == null &&
                (song.videoId.startsWith("webdav:") || song.videoId.startsWith("smb:")) ->
                RemoteArtworkStore.resolveArt(context, song)
            else -> song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: still
        }
    }
    return resolved
}

/**
 * Put the file's timestamp and size in its URL, without changing where it is.
 *
 * Coil keys its memory cache by the model string, so the same `file:` URI is
 * the same answer for ever — including the answer "there is nothing there".
 * A sidecar rewritten at the same path needs a different key to be read again;
 * the query is stripped by the file reader, so only the cache sees it.
 */
private fun versionedFileArtwork(value: String?): String? {
    value ?: return null
    if (!value.startsWith("file:", ignoreCase = true)) return value
    val file = runCatching { Uri.parse(value).path?.let(::File) }.getOrNull() ?: return value
    if (!file.isFile) return value
    val separator = if ('?' in value) '&' else '?'
    return "$value${separator}v=${file.lastModified()}-${file.length()}"
}
