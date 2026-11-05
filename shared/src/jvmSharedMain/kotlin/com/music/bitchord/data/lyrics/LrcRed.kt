package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/**
 * lrc.red — Apple Music TTML, per-syllable, filed by ISRC.
 *
 * Every document lives at `https://lrc.red/s/{ISRC}.ttml`, so a track whose
 * recording is already known costs one request and cannot come back as the
 * wrong edit. That is the usual case: [BiniLyrics] answers its searches out
 * of this same catalogue — its `lyricsUrl` points here — and
 * [LyricsRepository] hands the ISRC it finds to every source that can use one.
 *
 * Without one, lrc.red's own search (`/search.json?q=`) is asked with the
 * title and artist, and the hits are matched here rather than trusted in
 * order: the search is a free-text one and ranks "Bohemian Rhapsody
 * (Operatic Section / 2011 A Cappella Mix)" above the song itself. See [best].
 *
 * Line-synced entries are TTML too (`lrc:timing="Line"`), which [TtmlLyrics]
 * already reads, so one parser covers both. The document's own
 * `<audio lyricOffset>` is ignored, as it is for every Apple TTML here — the
 * stamps are written against the release audio, which is what is playing.
 * That is a deliberate refusal, not an oversight: unlike an LRC file's
 * `[offset:±N]` (honoured once in [EnhancedLrc.sheetOffsetMs]), this attribute
 * would have to be applied to a sheet that is the *record's*, on a video that
 * may not be — and 11-05 found lrc.red's own documents carry no such element,
 * so honouring it would move nothing and guessing its sign would move a whole
 * sheet the wrong way.
 */
object LrcRed {

    private const val BASE = "https://lrc.red/"

    /** `CC-XXX-YY-NNNNN` without the dashes — and nothing else goes into a path. */
    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    fun documentUrl(isrc: String): String? =
        isrc.trim().uppercase(Locale.ROOT).takeIf { ISRC.matches(it) }?.let { "${BASE}s/$it.ttml" }

    /**
     * [get] is how the document is fetched; [LyricsRepository] passes one that
     * shares the download with [BiniLyrics], which wants the very same file.
     */
    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        isrc: String? = null,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val known = isrc?.let(::documentUrl)
        if (known != null) document(known, get)?.let { return@withContext it }

        // A known recording lrc.red doesn't have — a local file's tag naming a
        // release it never indexed — still gets a search: the same song is
        // often catalogued under a sibling release's code.
        val hit = search(title, artist, durationMs) ?: return@withContext null
        val found = hit.isrc?.let(::documentUrl)?.takeIf { it != known } ?: return@withContext null
        document(found, get)
    }

    private suspend fun document(url: String, get: suspend (String) -> String?): List<LyricLine>? =
        get(url)?.let(TtmlLyrics::parse)?.takeIf { it.isNotEmpty() }

    private fun search(title: String, artist: String, durationMs: Long): Hit? {
        val url = "${BASE}search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$title $artist".trim())
            .build()
        val body = lyricsGet(url.toString()) ?: return null
        val hits = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()?.hits
            ?: return null
        return best(hits, title, artist, durationMs)
    }

    /**
     * The hit that is this recording, or null if none of them is sure to be.
     * See [RecordingMatch] for the shared name and length tests.
     */
    fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? =
        RecordingMatch.best(
            hits.map { hit ->
                RecordingMatch.Candidate(
                    value = hit,
                    title = hit.title,
                    artist = hit.artist,
                    seconds = hit.duration,
                    usable = hit.isrc?.let(::documentUrl) != null,
                )
            },
            title,
            artist,
            durationMs,
        )

    @Serializable
    data class Response(
        val hits: List<Hit>? = null,
        /** Paging cursor. The first page is all a match is looked for in. */
        val next: String? = null,
    )

    @Serializable
    data class Hit(
        val isrc: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val year: Int? = null,
        /** Seconds, fractional. */
        val duration: Double? = null,
    )
}
