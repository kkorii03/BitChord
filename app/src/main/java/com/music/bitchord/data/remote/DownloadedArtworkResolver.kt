package com.music.bitchord.data.remote

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.BoundedMemo
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
     * Resolved answers, with an empty string standing in for "none" because the
     * memo holds non-null values. Bounded: a long session opens far more
     * downloaded tracks than one screen, and an answer kept for all of them
     * grows the heap with the library.
     */
    private const val RESOLVED_ENTRIES = 512

    private val answers = ArtworkAnswerMemo(RESOLVED_ENTRIES)

    suspend fun resolve(context: Context, song: Song): String? = withContext(Dispatchers.IO) {
        answers.resolve(keyOf(song)) { resolveNow(context.applicationContext, song) }
    }

    /**
     * Forget what was resolved for [song] so the next ask reads the disk again.
     *
     * Called for the id whose cover save event just arrived. Only that row's
     * answer is dropped; every other resolved cover stays in the memo. A read
     * already running for the same key is retired too, so it cannot put its
     * pre-save answer back.
     */
    fun invalidate(song: Song) {
        answers.invalidateKey(keyOf(song))
    }

    /**
     * Forget every answer for [videoId], including one currently being read.
     *
     * The save event carries only the video id, while answers are filed under
     * every field that can change the picture. It also arrives at save time,
     * not when a row happens to compose, so a row that was off-screen when the
     * cover landed still cannot read the earlier miss.
     */
    fun invalidateForVideoId(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        answers.invalidatePrefix("$videoId\u0001")
    }

    private fun resolveNow(context: Context, song: Song): String? {
        val savedExists = localArtworkExists(song.thumbnailUrl)
        for (candidate in downloadedArtworkCandidates(song, savedExists)) {
            when (candidate.kind) {
                ArtworkCandidateKind.EMBEDDED -> {
                    val picture = embeddedPicture(context, candidate.value)
                    if (picture != null) {
                        persist(context, keyOf(song), picture)?.let { saved ->
                            if (saved.wrote) DownloadedArtworkEvents.coverSaved(song.videoId)
                            return saved.uri
                        }
                    }
                }
                ArtworkCandidateKind.SAVED_THUMBNAIL ->
                    if (readable(context, candidate.value)) return candidate.value
                ArtworkCandidateKind.RECORDED,
                ArtworkCandidateKind.YOUTUBE,
                -> return candidate.value
                ArtworkCandidateKind.DEFAULT -> return null
            }
        }
        return null
    }

    /**
     * Whether the recorded local picture is still on disk, without decoding it.
     *
     * A `file:` sidecar is a cheap `stat`; a `content:` URI is left to
     * [readable], which is the only place that can ask the resolver.
     */
    private fun localArtworkExists(value: String?): Boolean {
        value ?: return false
        return when {
            value.startsWith("file:", ignoreCase = true) ->
                runCatching {
                    Uri.parse(value).path?.let(::File)?.let { it.isFile && it.length() > 0L } == true
                }.getOrDefault(false)
            value.startsWith("content:", ignoreCase = true) -> true
            else -> false
        }
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

    private class Persisted(val uri: String, val wrote: Boolean)

    private fun persist(context: Context, key: String, bytes: ByteArray): Persisted? = runCatching {
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
        val wrote = !file.isFile || file.length() == 0L
        if (wrote) {
            file.outputStream().use { it.write(bytes) }
        }
        Persisted(Uri.fromFile(file).toString(), wrote)
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

/**
 * Remembered answers plus the one read that is allowed to produce each.
 *
 * A remembered miss is not timed: it is dropped only when a cover save retires
 * it, so a row recomposing on every frame never re-opens a dead path while a
 * sidecar written later no longer waits for an unrelated redraw. Retiring also
 * reaches a read that is already running, so the answer that started before
 * the save cannot finish afterwards and put the old miss back.
 */
internal class ArtworkAnswerMemo(private val limit: Int) {

    private val resolved = BoundedMemo<String, String>(limit)
    private val inFlight = ConcurrentHashMap<String, Flight>()
    private val lock = Any()

    private class Flight(val job: CompletableDeferred<String> = CompletableDeferred()) {
        val owned = java.util.concurrent.atomic.AtomicBoolean(false)

        @Volatile
        var retired = false
    }

    suspend fun resolve(key: String, compute: suspend () -> String?): String? {
        while (true) {
            val cached = synchronized(lock) { resolved.get(key) }
            if (!cached.isNullOrEmpty()) return cached
            if (cached != null) return null
            val flight = synchronized(lock) {
                inFlight[key] ?: Flight().also { inFlight[key] = it }
            }
            if (flight.owned.compareAndSet(false, true)) {
                try {
                    val value = compute() ?: ""
                    // A save that landed while this read was running retired
                    // the flight; its answer is the pre-save one and must not
                    // be written back over the fresh read.
                    commit(key, flight, value)
                    flight.job.complete(value)
                } catch (cancelled: CancellationException) {
                    flight.job.complete("")
                    throw cancelled
                } catch (error: Throwable) {
                    Log.d("BitChord", "cover lookup failed for $key: ${error.message}")
                    commit(key, flight, "")
                    flight.job.complete("")
                } finally {
                    synchronized(lock) {
                        if (inFlight[key] === flight) inFlight.remove(key)
                    }
                }
            }
            val value = try {
                flight.job.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ""
            }
            if (!flight.retired) return value.takeIf { it.isNotEmpty() }
            // The read this caller waited on started before the save. Ask
            // again so it receives the answer that includes the saved cover.
        }
    }

    fun invalidateKey(key: String) {
        synchronized(lock) {
            // Retire the flight before dropping the memo: a read that is
            // finishing right now must not write its pre-save answer back in
            // between the two, and the other order would let a new read see
            // the old answer while the flight is already gone.
            inFlight.remove(key)?.retired = true
            resolved.remove(key)
        }
    }

    fun invalidatePrefix(prefix: String) {
        synchronized(lock) {
            val iterator = inFlight.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    entry.value.retired = true
                    iterator.remove()
                }
            }
            resolved.removeMatching { it.startsWith(prefix) }
        }
    }

    private fun commit(key: String, flight: Flight, value: String) {
        synchronized(lock) {
            if (flight.retired || inFlight[key] !== flight) return
            // A real URI replaces an earlier "none" for the same key. A miss
            // is kept until a save event retires it, rather than for a delay.
            if (value.isEmpty()) {
                resolved.getOrPut(key) { value }
            } else {
                resolved.put(key, value)
            }
        }
    }
}
