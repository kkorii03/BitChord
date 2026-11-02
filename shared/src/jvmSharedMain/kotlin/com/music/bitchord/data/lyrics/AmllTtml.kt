package com.music.bitchord.data.lyrics

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Collections

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
 * see [RecordingMatch]. The id is remembered per song, so the query variants
 * the repository tries do not each ask iTunes again.
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
     * keeps a lookup made without a length apart from one made with it. A
     * remembered miss is a miss too: the same song is not asked for again this
     * session.
     */
    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        songKey: String? = null,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        // One id per song across the query variants, but a lookup made without
        // a length stays apart: it may have settled on a different edit.
        val length = if (durationMs <= 0L) "unknown" else ((durationMs + 5_000L) / 10_000L).toString()
        val key = "${songKey?.takeIf { it.isNotBlank() } ?: "$title\u0000$artist"}\u0000$length"
        val remembered = synchronized(appleIds) { appleIds[key] }
        val id = if (remembered != null) {
            remembered.id
        } else {
            val hits = search(title, artist, get)
            val found = hits?.let { best(it, title, artist, durationMs)?.trackId }
            // Only a search that got an answer is worth remembering: offline
            // is no verdict on the song, and must be retried by a later name.
            if (hits != null) synchronized(appleIds) { appleIds[key] = AppleId(found) }
            found
        }
        val log = remembered == null
        if (id == null) {
            if (log) TrackLog.i(TAG, "lyrics source ttml: miss id=unknown lines=0")
            return@withContext null
        }
        val lines = get(documentUrl(id))?.let(TtmlLyrics::parse)?.takeIf { it.isNotEmpty() }
        if (lines == null) {
            if (log) TrackLog.i(TAG, "lyrics source ttml: miss id=$id lines=0")
            return@withContext null
        }
        if (log) TrackLog.i(TAG, "lyrics source ttml: hit id=$id lines=${lines.size}")
        lines
    }

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
        RecordingMatch.best(
            hits.map { hit ->
                RecordingMatch.Candidate(
                    value = hit,
                    title = hit.trackName,
                    artist = hit.artistName,
                    seconds = hit.trackTimeMillis?.div(1000.0),
                    usable = hit.trackId != null,
                )
            },
            title,
            artist,
            durationMs,
        )

    private class AppleId(val id: Long?)

    private val appleIds: MutableMap<String, AppleId> = Collections.synchronizedMap(
        object : LinkedHashMap<String, AppleId>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, AppleId>) = size > REMEMBERED
        },
    )

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
