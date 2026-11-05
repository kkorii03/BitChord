package com.music.bitchord.data.lyrics

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Collections
import java.util.Locale

/**
 * AMLL TTML DB — human-timed word-level lyrics, filed by platform id.
 *
 * The database is a GitHub repository: contributor-made TTML under CC0, with
 * foreign material kept under its provider's terms. No login or key is needed.
 * Its Apple Music folder is keyed by Apple's numeric track id, so the lookup is
 * two small key-less requests: the iTunes Search API names the recording, then
 * the raw file at `am-lyrics/<trackId>.ttml` is fetched.
 *
 * The iTunes result is also the song guard: title, artists and length all have
 * to agree with what is playing before its id is allowed to name a document —
 * see [RecordingMatch]. A hit is remembered per song, so the query variants
 * the repository tries reuse it. A miss is remembered per *query* instead: a
 * noisy cover title must not stop a later round asking under the real name.
 * Concurrent lookups of the same query share one request.
 */
object AmllTtml {

    private const val TAG = "Lyrics"
    private const val SEARCH = "https://itunes.apple.com/search"
    private const val RAW =
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/am-lyrics/"
    private const val SEARCH_LIMIT = 10
    private const val REMEMBERED = 64

    fun documentUrl(trackId: Long): String = "$RAW$trackId.ttml"

    /**
     * [songKey] identifies the song across the repository's query variants (it
     * passes the video id); without one the name is the key. The length bucket
     * keeps a lookup made without a length apart from one made with it.
     *
     * A hit is cached per song. A miss is cached per normalised query instead,
     * so a later round with the real or catalogue-original name still asks
     * iTunes. Callers asking the same query at the same time await one
     * request, and only the caller that made it writes the log line.
     */
    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        songKey: String? = null,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val lookup = appleId(title, artist, durationMs, songKey, get)
        val id = lookup.id ?: run {
            if (lookup.fresh) {
                TrackLog.i(TAG, "lyrics source ttml: miss id=unknown lines=0 reason=${lookup.reason ?: "unknown"}")
            }
            return@withContext null
        }
        val lines = get(documentUrl(id))?.let(TtmlLyrics::parse)?.takeIf { it.isNotEmpty() }
        if (lines == null) {
            // The recording iTunes matched has no document (a cover, an edit the
            // DB lacks): drop it for the song and mark this query a miss, so the
            // repository's later name variants search again instead of reusing
            // a dead id.
            forgetDeadId(lookup)
            if (lookup.fresh) TrackLog.i(TAG, "lyrics source ttml: miss id=$id lines=0")
            return@withContext null
        }
        if (lookup.fresh) TrackLog.i(TAG, "lyrics source ttml: hit id=$id lines=${lines.size}")
        lines
    }

    /** The iTunes id for this song, or null; [fresh] means this call searched. */
    private suspend fun appleId(
        title: String,
        artist: String,
        durationMs: Long,
        songKey: String?,
        get: suspend (String) -> String?,
    ): AppleLookup {
        // One id per song across the query variants, but a lookup made without
        // a length stays apart: it may have settled on a different edit.
        val length = if (durationMs <= 0L) "unknown" else ((durationMs + 5_000L) / 10_000L).toString()
        val song = songKey?.takeIf { it.isNotBlank() } ?: "${title}\u0000$artist"
        val hitKey = "$song\u0000$length"
        val missKey = "${normalized(title)}\u0000${normalized(artist)}\u0000$length"

        synchronized(hits) { hits[hitKey] }?.let { return AppleLookup(it.id, false, hitKey, missKey) }
        // A miss is only a verdict on this name and length, never on the song:
        // the repository's later rounds try the plain and original names.
        synchronized(misses) { if (missKey in misses) return AppleLookup(null, false, hitKey, missKey) }

        var owned: CompletableDeferred<AppleLookup>? = null
        val flight = synchronized(flights) {
            flights[missKey] ?: CompletableDeferred<AppleLookup>().also {
                flights[missKey] = it
                owned = it
            }
        }
        // Someone else is already asking this exact query: share their answer.
        // An owner that was cancelled (a prefetch losing its race) leaves no
        // answer, and this caller asks for itself rather than inherit that
        // foreign cancellation.
        if (owned == null) {
            val shared = flight.await()
            return if (shared.ownerCancelled) {
                appleId(title, artist, durationMs, songKey, get)
            } else {
                shared.copy(fresh = false)
            }
        }
        return try {
            val results = search(title, artist, get)
            val found = results?.let { best(it, title, artist, durationMs)?.trackId }
            // Why the search came back empty, for the caller's miss line: an
            // unanswered request and a refused one are both a null here.
            val reason = when {
                results == null -> "http"
                found == null -> missReason(results, title, artist, durationMs)
                else -> null
            }
            val answer = AppleLookup(found, true, hitKey, missKey, reason = reason)
            if (results != null) {
                // Only a search that got an answer is worth remembering:
                // offline is no verdict on the song, and must be retried by a
                // later name.
                if (found != null) {
                    synchronized(hits) { hits[hitKey] = AppleId(found) }
                } else {
                    synchronized(misses) { misses.add(missKey) }
                }
            }
            flight.complete(answer)
            answer
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            flight.complete(AppleLookup(null, false, hitKey, missKey, ownerCancelled = true))
            throw cancelled
        } catch (error: Throwable) {
            flight.completeExceptionally(error)
            throw error
        } finally {
            synchronized(flights) { flights.remove(missKey) }
        }
    }

    /** Case and spacing folded, so equivalent queries share a miss or flight. */
    private fun normalized(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")

    /** The iTunes hits, or null when the request or its answer failed. */
    private suspend fun search(
        title: String,
        artist: String,
        get: suspend (String) -> String?,
    ): List<Hit>? {
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("term", "$title $artist".trim())
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        val body = get(url.toString()) ?: return null
        return parseSearch(body)
    }

    internal fun parseSearch(body: String): List<Hit>? =
        runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()?.results

    /** The hit that is this recording, or null; see [RecordingMatch]. */
    internal fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? =
        RecordingMatch.best(candidates(hits), title, artist, durationMs)

    /** Why [best] found nothing, for the miss log line; see [RecordingMatch.missReason]. */
    internal fun missReason(hits: List<Hit>, title: String, artist: String, durationMs: Long): String =
        RecordingMatch.missReason(candidates(hits), title, artist, durationMs)

    private fun candidates(hits: List<Hit>): List<RecordingMatch.Candidate<Hit>> =
        hits.map { hit ->
            RecordingMatch.Candidate(
                value = hit,
                title = hit.trackName,
                artist = hit.artistName,
                seconds = hit.trackTimeMillis?.div(1000.0),
                usable = hit.trackId != null,
            )
        }

    private class AppleId(val id: Long?)

    private data class AppleLookup(
        val id: Long?,
        val fresh: Boolean,
        val hitKey: String,
        val missKey: String,
        val ownerCancelled: Boolean = false,
        /** Why [id] is null, for the miss line: "http", or what the rows failed. */
        val reason: String? = null,
    )

    private fun forgetDeadId(lookup: AppleLookup) {
        synchronized(hits) { hits.remove(lookup.hitKey) }
        synchronized(misses) { misses.add(lookup.missKey) }
    }

    /** Hits live per song; misses per query; in-flight searches per query. */
    private val hits: MutableMap<String, AppleId> = object : LinkedHashMap<String, AppleId>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, AppleId>) = size > REMEMBERED
    }

    private val misses: MutableSet<String> = Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > REMEMBERED
        },
    )

    private val flights: MutableMap<String, CompletableDeferred<AppleLookup>> = mutableMapOf()

    private val WHITESPACE = Regex("""\s+""")

    @Serializable
    internal data class Response(
        val resultCount: Int? = null,
        val results: List<Hit>? = null,
    )

    @Serializable
    internal data class Hit(
        val trackId: Long? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val trackTimeMillis: Long? = null,
    )
}
