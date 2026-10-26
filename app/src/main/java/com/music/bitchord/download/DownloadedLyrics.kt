package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a fresh provider sheet over the lyrics already saved with a
 * downloaded track, keeping the rest of the file's tags and cover.
 *
 * This is the write side of [com.music.bitchord.data.lyrics.EmbeddedLyrics]:
 * an offline package keeps its words in a `lyrics.lrc` beside the playlist,
 * and a tagged file keeps them in its own lyrics field. A re-check that finds
 * a better sheet has to land in the same place the player reads, or the next
 * play would show the old words again.
 *
 * Returns true only when the new text is on disk. The caller treats false as
 * "not replaced" and leaves the check version unset, so the next playback may
 * try again.
 */
internal object DownloadedLyrics {

    private const val TAG = "BitChord"
    private const val COPY_BUFFER = 64 * 1024

    /** Plenty for a FLAC metadata chain; a longer one falls back to no write. */
    private const val FLAC_HEAD_BYTES = 16 * 1024 * 1024

    suspend fun replace(context: Context, uriString: String, lyrics: LyricsTag.Embeddable): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                when (extension(context, uri)) {
                    "m3u8" -> writeSidecar(uri, lyrics)
                    "flac" -> replaceFlac(context, uri, lyrics)
                    "m4a", "mp4", "m4b", "alac" -> replaceBytes(context, uri, lyrics) { bytes ->
                        Mp4Tagger.replaceLyrics(bytes, lyrics.plain, lyrics.enhanced)
                    }
                    "webm" -> replaceBytes(context, uri, lyrics) { bytes ->
                        WebmTagger.replaceLyrics(bytes, lyrics.plain, lyrics.enhanced)
                    }
                    else -> false
                }
            }.onFailure {
                Log.w(TAG, "could not replace downloaded lyrics in $uriString: ${it.message}")
            }.getOrDefault(false)
        }

    private fun writeSidecar(uri: Uri, lyrics: LyricsTag.Embeddable): Boolean {
        if (uri.scheme != "file") return false
        val playlist = uri.path?.let(::File)
            ?.takeIf { it.name.endsWith(".m3u8", ignoreCase = true) }
            ?: return false
        val target = File(playlist.parentFile ?: return false, "lyrics.lrc")
        val text = lyrics.enhanced ?: lyrics.plain
        if (text.isBlank()) return false
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        return runCatching {
            temporary.writeText(text)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.isFile && target.length() > 0 && carriesFresh(target.readText(), lyrics)
        }.onFailure { temporary.delete() }.getOrDefault(false)
    }

    private fun replaceBytes(
        context: Context,
        uri: Uri,
        lyrics: LyricsTag.Embeddable,
        transform: (ByteArray) -> ByteArray?,
    ): Boolean {
        val original = readAll(context, uri) ?: return false
        val tagged = transform(original) ?: return false
        if (tagged === original || !carriesFresh(tagged, lyrics)) return false
        return writeAll(context, uri, tagged)
    }

    /**
     * FLAC metadata lives at the front, so only that is held in memory and the
     * frames are streamed through. A lossless download is exactly the file
     * this matters for.
     */
    private fun replaceFlac(context: Context, uri: Uri, lyrics: LyricsTag.Embeddable): Boolean {
        val head = readPrefix(context, uri, FLAC_HEAD_BYTES) ?: return false
        val rewrite = FlacTagger.replaceLyricsHeader(head, lyrics.plain, lyrics.enhanced) ?: return false
        if (!carriesFresh(rewrite.metadata, lyrics)) return false
        val temporary = File(context.cacheDir, "lyrics-${System.nanoTime()}.flac")
        return runCatching {
            temporary.outputStream().buffered().use { sink ->
                sink.write(rewrite.metadata)
                openInput(context, uri)?.use { source ->
                    source.skipFully(rewrite.audioStart.toLong())
                    source.copyTo(sink, COPY_BUFFER)
                } ?: error("could not reopen $uri")
            }
            replaceWith(context, uri, temporary)
            true
        }.onFailure {
            Log.w(TAG, "could not replace FLAC lyrics in $uri: ${it.message}")
            temporary.delete()
        }.getOrDefault(false)
    }


    /** Confirms the reader will see the new header before the old words. */
    private fun carriesFresh(bytes: ByteArray, lyrics: LyricsTag.Embeddable): Boolean {
        val read = EmbeddedLyrics.fromBytes(bytes) ?: return false
        val header = lyrics.plain.lineSequence().firstOrNull { it.startsWith("[re:BitChord ") }
        return if (header != null) read.contains(header) else read.isNotBlank()
    }

    private fun carriesFresh(text: String, lyrics: LyricsTag.Embeddable): Boolean {
        val header = lyrics.plain.lineSequence().firstOrNull { it.startsWith("[re:BitChord ") }
        return if (header != null) text.contains(header) else text.isNotBlank()
    }

    private fun replaceWith(context: Context, uri: Uri, temporary: File) {
        if (uri.scheme == "file") {
            val target = File(requireNotNull(uri.path))
            if (temporary.renameTo(target)) return
        }
        temporary.inputStream().use { source ->
            val sink = if (uri.scheme == "file") {
                File(requireNotNull(uri.path)).outputStream()
            } else {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("could not open $uri for writing")
            }
            sink.use { source.copyTo(it, COPY_BUFFER) }
        }
        temporary.delete()
    }

    private fun extension(context: Context, uri: Uri): String {
        val name = if (uri.scheme == "file") {
            uri.path
        } else {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull() ?: uri.path
        }
        return name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun readAll(context: Context, uri: Uri): ByteArray? = runCatching {
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }.onFailure { Log.w(TAG, "could not read $uri for lyrics: ${it.message}") }.getOrNull()

    private fun writeAll(context: Context, uri: Uri, bytes: ByteArray): Boolean = runCatching {
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).writeBytes(bytes)
        } else {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("could not open $uri for writing")
        }
        true
    }.onFailure { Log.w(TAG, "could not write lyrics to $uri: ${it.message}") }.getOrDefault(false)

    private fun readPrefix(context: Context, uri: Uri, max: Int): ByteArray? = runCatching {
        openInput(context, uri)?.use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER)
            while (out.size() < max) {
                val read = source.read(buffer, 0, minOf(buffer.size, max - out.size()))
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.onFailure { Log.w(TAG, "could not read the start of $uri: ${it.message}") }.getOrNull()

    private fun openInput(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (read() == -1) error("file ended ${left}B before its audio frames")
            left--
        }
    }
}
