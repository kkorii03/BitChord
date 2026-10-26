package com.music.bitchord.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MediaMetadata
import com.music.bitchord.data.cover.ArtworkSource
import com.music.bitchord.data.cover.artworkSelection
import com.music.bitchord.data.cover.isLocalArtworkUri
import com.music.bitchord.data.cover.videoThumbnailUrl
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a private `file://` cover out of [MediaMetadata.artworkUri].
 *
 * The sidecar [com.music.bitchord.download.MediaTagger] saves lives under the
 * app's own `files/download-artwork`, so System UI, Android Auto and any other
 * process reading the session metadata cannot open it. That is the
 * `FileNotFoundException: ... ENOENT` the lock screen and the media controls
 * logged eleven times.
 *
 * A readable sidecar is handed over through the app's existing [FileProvider]
 * as a `content://` URI, with a read grant for System UI. A sidecar that has
 * gone is not put in the metadata at all: the video's own https still is used
 * instead, which every process can read. Streaming tracks are untouched.
 */
internal object MediaSessionArtwork {

    /** The process that draws the lock screen, the notification and the media controls. */
    private const val SYSTEM_UI = "com.android.systemui"

    private val granted = ConcurrentHashMap.newKeySet<String>()

    /**
     * The artwork URI to put on a [androidx.media3.common.MediaItem].
     *
     * Doing this when the item is built means the session's metadata already
     * holds the readable form, so the lyric-subtitle updates that rebuild
     * metadata every second do not re-state the cover.
     */
    fun uriFor(context: Context?, song: Song): String? {
        val recorded = song.thumbnailUrl
        val selection = artworkSelection(
            thumbnailUrl = recorded,
            videoId = song.videoId,
            localArtworkExists = localArtworkExists(recorded),
        )
        return when (selection.source) {
            ArtworkSource.LOCAL ->
                context?.let { readableContentUri(it, selection.value) }
                    ?: videoThumbnailUrl(song.videoId)?.artworkAt(NOTIFICATION_ART_PX)
            ArtworkSource.REMOTE -> selection.value?.artworkAt(NOTIFICATION_ART_PX)
            ArtworkSource.NONE -> null
        }
    }

    /** As [uriFor], for a browse item that has a URL but no [Song]. */
    fun uriForUrl(context: Context?, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!isLocalArtworkUri(url)) return url.artworkAt(NOTIFICATION_ART_PX)
        if (!localArtworkExists(url)) return null
        return context?.let { readableContentUri(it, url) }
    }

    fun apply(context: Context, metadata: MediaMetadata, videoId: String?): MediaMetadata {
        val recorded = metadata.artworkUri?.toString()
        val selection = artworkSelection(
            thumbnailUrl = recorded,
            videoId = videoId,
            localArtworkExists = localArtworkExists(recorded),
        )
        val uri = when (selection.source) {
            ArtworkSource.LOCAL -> readableContentUri(context, selection.value)
                ?: videoThumbnailUrl(videoId)?.artworkAt(NOTIFICATION_ART_PX)
            ArtworkSource.REMOTE -> selection.value?.artworkAt(NOTIFICATION_ART_PX)
            ArtworkSource.NONE -> null
        }
        // Only the playing track's metadata reaches another process, so the
        // grant is spent here rather than on every item a queue builds.
        uri?.let(Uri::parse)?.let { grantRead(context, it) }
        if (uri == recorded) return metadata
        return metadata.buildUpon().setArtworkUri(uri?.let(Uri::parse)).build()
    }

    private fun localArtworkExists(value: String?): Boolean {
        value ?: return false
        return when {
            value.startsWith("file:", ignoreCase = true) -> runCatching {
                Uri.parse(value).path?.let(::File)?.let { it.isFile && it.length() > 0L } == true
            }.getOrDefault(false)
            // A content URI is already the readable form; leave it alone.
            value.startsWith("content:", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun readableContentUri(context: Context, value: String?): String? {
        value ?: return null
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> value
            "file" -> {
                val file = uri.path?.let(::File) ?: return null
                if (!file.isFile || file.length() == 0L) return null
                runCatching {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    ).toString()
                }.getOrNull()
            }
            else -> null
        }
    }

    /**
     * Grants are per URI and package, so one pass per URI is enough. Only
     * content URIs need one; an https still is readable by every process.
     */
    private fun grantRead(context: Context, uri: Uri) {
        if (!uri.scheme.equals("content", ignoreCase = true)) return
        if (!granted.add(uri.toString())) return
        runCatching {
            context.grantUriPermission(SYSTEM_UI, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
