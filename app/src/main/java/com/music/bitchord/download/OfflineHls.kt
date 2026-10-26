package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.lyrics.WORD_LYRICS_FIELD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * An offline copy of an unencrypted HLS media playlist.  This deliberately
 * keeps fMP4 samples untouched: Media3's HLS reader is the same one that
 * plays the network stream, and therefore supports codecs MediaMuxer cannot
 * put in a fresh MP4 (notably FLAC and Dolby variants).
 */
internal object OfflineHls {
    suspend fun save(
        context: Context,
        id: String,
        url: String,
        headers: Map<String, String>,
        onProgress: (Long, Long) -> Unit,
        lyrics: LyricsTag.Embeddable?,
        artwork: MediaTagger.Artwork?,
    ): Uri = withContext(Dispatchers.IO) {
        val root = File(File(context.filesDir, "offline-hls"), id.hashCode().toUInt().toString(16))
        // A process killed between the two renames of a swap leaves the old
        // package as `.old` and nothing at the final path. Put it back before
        // doing anything else.
        val backup = File(root.parentFile, "${root.name}.old")
        if (!root.exists() && backup.exists() && !backup.renameTo(root)) {
            error("Could not restore the offline HLS package")
        }
        // A replacement must not delete the package it is replacing until the
        // new one is complete. Everything is written beside the final folder
        // and swapped in only after the last fragment and the playlist are on
        // disk; a failure leaves the old package untouched.
        val staging = File(root.parentFile, "${root.name}.new")
        staging.deleteRecursively()
        if (!staging.mkdirs()) error("Could not create offline HLS package")
        try {
            fun request(target: String) = Request.Builder().url(target).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            val playlist = Http.client.newCall(request(url)).execute().use { response ->
                if (!response.isSuccessful) error("HLS playlist failed (HTTP ${response.code})")
                response.body?.string() ?: error("Empty HLS playlist")
            }
            if (!playlist.startsWith("#EXTM3U")) error("Invalid HLS playlist")
            if (playlist.contains("#EXT-X-KEY:") && !playlist.contains("METHOD=NONE")) {
                error("Encrypted HLS cannot be saved")
            }
            val base = url.toHttpUrlOrNull() ?: error("Invalid HLS URL")
            val references = playlist.lineSequence().map(String::trim).filter {
                it.isNotEmpty() && !it.startsWith('#')
            }.toMutableList()
            Regex("""#EXT-X-MAP:.*URI=\"([^\"]+)\"""").find(playlist)?.groupValues?.get(1)?.let {
                references.add(0, it)
            }
            if (references.isEmpty()) error("HLS playlist has no fragments")
            val localNames = linkedMapOf<String, String>()
            references.distinct().forEachIndexed { index, remote ->
                coroutineContext.ensureActive()
                val target = base.resolve(remote)?.toString() ?: error("Invalid HLS fragment")
                val local = "segment-${index.toString().padStart(5, '0')}.m4s"
                Http.client.newCall(request(target)).execute().use { response ->
                    if (!response.isSuccessful) error("HLS fragment failed (HTTP ${response.code})")
                    response.body?.byteStream()?.use { input -> File(staging, local).outputStream().use(input::copyTo) }
                        ?: error("Empty HLS fragment")
                }
                localNames[remote] = local
                onProgress((index + 1).toLong(), references.distinct().size.toLong())
            }
            val localPlaylist = playlist
                .replace(Regex("""(URI=\")([^\"]+)(\")""")) { match ->
                    match.groupValues[1] + (localNames[match.groupValues[2]] ?: match.groupValues[2]) + match.groupValues[3]
                }
                .lineSequence().joinToString("\n") { line -> localNames[line.trim()] ?: line }
            File(staging, "playlist.m3u8").writeText(localPlaylist)
            if (lyrics != null) {
                File(staging, "lyrics.lrc").writeText(lyrics.enhanced ?: lyrics.plain.orEmpty())
            } else {
                // A lyrics server being down must not cost the old package the
                // lyrics it already had.
                File(root, "lyrics.lrc")
                    .takeIf { it.isFile }
                    ?.copyTo(File(staging, "lyrics.lrc"), overwrite = true)
            }
            if (artwork != null) {
                File(staging, "cover.${if (artwork.mime.contains("png")) "png" else "jpg"}").writeBytes(artwork.bytes)
            } else {
                root.listFiles()
                    ?.firstOrNull { it.name.startsWith("cover.") }
                    ?.copyTo(File(staging, it.name), overwrite = true)
            }
            commit(staging, root)
            Uri.fromFile(File(root, "playlist.m3u8"))
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Put a finished [staging] package in [root]'s place without losing the old one on failure. */
    private fun commit(staging: File, root: File) {
        val backup = File(root.parentFile, "${root.name}.old")
        backup.deleteRecursively()
        if (root.exists() && !root.renameTo(backup)) {
            error("Could not replace the offline HLS package")
        }
        if (!staging.renameTo(root)) {
            backup.renameTo(root)
            error("Could not finish the offline HLS package")
        }
        backup.deleteRecursively()
    }
}
