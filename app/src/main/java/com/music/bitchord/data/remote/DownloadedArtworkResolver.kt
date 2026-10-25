package com.music.bitchord.data.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.cover.ArtworkCandidateKind
import com.music.bitchord.data.cover.downloadedArtworkCandidates
import com.music.bitchord.data.model.Song
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Resolves a downloaded track's cover by trying its candidates exactly once.
 *
 * The list itself is pure and lives in
 * [com.music.bitchord.data.cover.downloadedArtworkCandidates]. This side only
 * touches the disk: it pulls the picture out of the audio file, checks that
 * the saved sidecar is still readable, and otherwise falls back to YouTube's
 * still. The answer — including "there is none" — is remembered for the
 * process so a dead path is not re-opened on every recomposition, which is
 * what used to turn one missing cover into a request every 1.5 seconds.
 */
object DownloadedArtworkResolver {

    private const val TAG = "BitChord"

    /**
     * Resolved answers, with an empty string standing in for "none" because a
     * [ConcurrentHashMap] may not hold null. Concurrent asks for different
     * tracks run independently; asks for one track share a single extraction.
     */
    private val resolved = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val claimed = ConcurrentHashMap.newKeySet<String>()
    private val inFlightLock = Mutex()

    suspend fun resolve(context: Context, song: Song): String? = withContext(Dispatchers.IO) {
        val key = keyOf(song)
        resolved[key]?.let { return@withContext it.takeIf { value -> value.isNotEmpty() } }
        val job = inFlightLock.withLock { inFlight.getOrPut(key) { CompletableDeferred() } }
        if (claimed.add(key)) {
            try {
                val value = resolveNow(context.applicationContext, song) ?: ""
                resolved[key] = value
                job.complete(value)
            } catch (cancelled: CancellationException) {
                job.complete("")
                throw cancelled
            } catch (error: Throwable) {
                Log.d(TAG, "cover lookup failed for ${song.videoId}: ${error.message}")
                resolved[key] = ""
                job.complete("")
            } finally {
                claimed.remove(key)
                inFlightLock.withLock { inFlight.remove(key, job) }
            }
        }
        val value = try {
            job.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ""
        }
        value.takeIf { it.isNotEmpty() }
    }

    private fun resolveNow(context: Context, song: Song): String? {
        for (candidate in downloadedArtworkCandidates(song)) {
            when (candidate.kind) {
                ArtworkCandidateKind.EMBEDDED -> {
                    val picture = embeddedPicture(context, candidate.value)
                    if (picture != null) {
                        persist(context, keyOf(song), picture)?.let { return it }
                    }
                }
                ArtworkCandidateKind.SAVED_THUMBNAIL ->
                    if (readable(context, candidate.value)) return candidate.value
                ArtworkCandidateKind.YOUTUBE -> return candidate.value
                ArtworkCandidateKind.DEFAULT -> return null
            }
        }
        return null
    }

    private fun embeddedPicture(context: Context, value: String?): ByteArray? {
        value ?: return null
        return runCatching {
            val uri = Uri.parse(value)
            val retriever = MediaMetadataRetriever()
            try {
                when (uri.scheme?.lowercase(Locale.ROOT)) {
                    "file" -> retriever.setDataSource(uri.path ?: return@runCatching null)
                    "content" -> retriever.setDataSource(context, uri)
                    else -> return@runCatching null
                }
                retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
            } finally {
                runCatching { retriever.release() }
            }
        }.onFailure {
            Log.d(TAG, "no embedded cover in $value: ${it.message}")
        }.getOrNull()
    }

    private fun readable(context: Context, value: String?): Boolean {
        value ?: return false
        return runCatching {
            val uri = Uri.parse(value)
            val stream = when (uri.scheme?.lowercase(Locale.ROOT)) {
                "file" -> uri.path?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                    ?.inputStream()
                "content" -> context.contentResolver.openInputStream(uri)
                else -> null
            } ?: return false
            stream.use {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(it, null, options)
                options.outWidth > 0 && options.outHeight > 0
            }
        }.getOrDefault(false)
    }

    private fun persist(context: Context, key: String, bytes: ByteArray): String? = runCatching {
        val dir = File(context.cacheDir, "downloaded-art").apply { mkdirs() }
        val extension = if (bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
            "png"
        } else {
            "jpg"
        }
        val name = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".$extension"
        val file = File(dir, name)
        if (!file.isFile || file.length() == 0L) {
            file.outputStream().use { it.write(bytes) }
        }
        Uri.fromFile(file).toString()
    }.onFailure {
        Log.d(TAG, "could not save embedded cover: ${it.message}")
    }.getOrNull()

    private fun keyOf(song: Song): String =
        listOf(
            song.videoId,
            song.localUri.orEmpty(),
            song.thumbnailUrl.orEmpty(),
            song.localDateModifiedSeconds?.toString().orEmpty(),
        ).joinToString("\u0001")
}
