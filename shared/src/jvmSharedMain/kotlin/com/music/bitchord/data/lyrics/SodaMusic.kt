package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Soda Music (汽水音乐) word timing — the Chinese catalogue's official
 * per-syllable stamps, fetched from two unauthenticated web endpoints.
 *
 * One chain per song and no other request: the name search names the track id,
 * then that id answers the sheet. The row is held to [RecordingMatch] like
 * every other catalogue row, so a namesake or a different edit is refused
 * rather than timed.
 *
 * The sheet is KRC-shaped — `[line start,line length]<word offset,word
 * length,0>text` — the same body KuGou ships from its KRC files, so
 * [KaraokeLrc] reads it once rather than a second parser being written. Only
 * word-stamped rows are taken; a line-only answer has nothing this source adds.
 *
 * Endpoints and shape reconfirmed 2026-11-07; see `ship\lanes\soda.md`. Every
 * failure — transport, non-JSON, an unexpected shape, an oversized body, a
 * row that is not this recording — is no answer, so the next source gets a
 * turn instead of wrong words being shown in time with the right song.
 */
object SodaMusic {

    private const val SEARCH = "https://api.qishui.com/luna/search/track"
    private const val DETAIL = "https://beta-luna.douyin.com/luna/h5/seo_track"

    /** The web player's fixed app number, the only parameter the search needs. */
    private const val AID = "386088"
    private const val COUNT = "20"

    // Bounds at the trust boundary. The live answers measured 92-119 KB of
    // search and 181-216 KB of detail; a sheet of 3.6-7.4 KB. Anything past
    // these is refused rather than trimmed.
    private const val MAX_SEARCH_BODY = 1_000_000
    private const val MAX_DETAIL_BODY = 1_000_000
    private const val MAX_GROUPS = 50
    private const val MAX_ROWS = 200
    private const val MAX_ARTISTS = 16
    private const val MAX_ID = 64
    private const val MAX_NAME = 512
    private const val MAX_LYRIC_CHARS = 1_000_000
    private const val MAX_LINES = 2_000
    private const val MAX_LINE_CHARS = 1_024
    /** A CJK line can put a stamp on every character; the live rows had at most 14. */
    private const val MAX_WORDS_LINE = 128
    private const val MAX_WORD_CHARS = 64

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        runCatching { fetch(title, artist, durationMs, get) }.getOrNull()
    }

    private suspend fun fetch(
        title: String,
        artist: String,
        durationMs: Long,
        get: suspend (String) -> String?,
    ): List<LyricLine>? {
        if (title.isBlank()) return null
        val searchUrl = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("q", "$artist $title".trim())
            .addQueryParameter("cursor", "0")
            .addQueryParameter("count", COUNT)
            .addQueryParameter("aid", AID)
            .build()
            .toString()
        val searchBody = get(searchUrl) ?: return null
        if (searchBody.length > MAX_SEARCH_BODY) return null
        val hits = runCatching { lyricsJson.decodeFromString<SearchResponse>(searchBody) }.getOrNull()
            ?.let(::hitsOf) ?: return null
        val hit = best(hits, title, artist, durationMs) ?: return null
        val detailUrl = DETAIL.toHttpUrl().newBuilder()
            .addQueryParameter("track_id", hit.id)
            .addQueryParameter("device_platform", "web")
            .build()
            .toString()
        val detailBody = get(detailUrl) ?: return null
        if (detailBody.length > MAX_DETAIL_BODY) return null
        val answer = runCatching { lyricsJson.decodeFromString<DetailResponse>(detailBody) }.getOrNull()
            ?: return null
        return linesOf(answer, hit.id)
    }

    /** The catalogue rows, or null when the answer is larger than a search answer can be. */
    private fun hitsOf(response: SearchResponse): List<Hit>? {
        if (response.resultGroups.size > MAX_GROUPS) return null
        val hits = ArrayList<Hit>()
        for (group in response.resultGroups) {
            // The same answer also carries artist, album and playlist groups.
            if (group.id != "tracks") continue
            if (group.data.size > MAX_ROWS) return null
            for (row in group.data) {
                val track = row.entity?.track ?: continue
                val id = track.id?.takeIf { it.isNotBlank() } ?: continue
                val name = track.name.orEmpty()
                if (id.length > MAX_ID || name.length > MAX_NAME) return null
                if (track.artists.size > MAX_ARTISTS) return null
                val names = track.artists.mapNotNull { it.name?.takeIf(String::isNotBlank) }
                if (names.any { it.length > MAX_NAME }) return null
                hits += Hit(
                    id = id,
                    title = name,
                    artist = names.joinToString("/"),
                    seconds = track.duration?.let { it / 1000.0 },
                )
            }
        }
        return hits
    }

    /** The hit that is this recording, or null; see [RecordingMatch]. */
    internal fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? =
        RecordingMatch.best(
            hits.map { hit ->
                RecordingMatch.Candidate(
                    value = hit,
                    title = hit.title,
                    artist = hit.artist,
                    seconds = hit.seconds,
                )
            },
            title,
            artist,
            durationMs,
        )

    /** The word rows of the sheet, or null when it is not a word sheet of the track asked for. */
    private fun linesOf(answer: DetailResponse, trackId: String): List<LyricLine>? {
        // The detail must be the track the search named, not another one.
        if (answer.seoTrack?.track?.id != trackId) return null
        val content = answer.lyric?.content?.takeIf { it.isNotBlank() } ?: return null
        if (content.length > MAX_LYRIC_CHARS) return null
        val lines = runCatching { KaraokeLrc.parse(content) }.getOrNull() ?: return null
        if (lines.isEmpty() || lines.size > MAX_LINES) return null
        if (lines.any { line ->
                line.text.length > MAX_LINE_CHARS ||
                    line.words.size > MAX_WORDS_LINE ||
                    line.words.any { it.text.length > MAX_WORD_CHARS }
            }
        ) {
            return null
        }
        return lines.takeIf { rows -> rows.any { it.isWordSynced } }
    }

    /** One catalogue row, flattened onto the fields [RecordingMatch] reads. */
    internal data class Hit(val id: String, val title: String, val artist: String, val seconds: Double?)

    @Serializable
    internal data class SearchResponse(
        @SerialName("result_groups") val resultGroups: List<Group> = emptyList(),
    )

    @Serializable
    internal data class Group(val id: String? = null, val data: List<Row> = emptyList())

    @Serializable
    internal data class Row(val entity: Entity? = null)

    @Serializable
    internal data class Entity(val track: Track? = null)

    @Serializable
    internal data class Track(
        val id: String? = null,
        val name: String? = null,
        val artists: List<Artist> = emptyList(),
        /** Milliseconds. */
        val duration: Long? = null,
    )

    @Serializable
    internal data class Artist(val name: String? = null)

    @Serializable
    internal data class DetailResponse(
        val lyric: Lyric? = null,
        @SerialName("seo_track") val seoTrack: SeoTrack? = null,
    )

    @Serializable
    internal data class Lyric(val content: String? = null)

    @Serializable
    internal data class SeoTrack(val track: DetailTrack? = null)

    @Serializable
    internal data class DetailTrack(val id: String? = null)
}