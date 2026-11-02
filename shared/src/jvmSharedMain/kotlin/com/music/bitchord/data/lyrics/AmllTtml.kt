package com.music.bitchord.data.lyrics

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

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
 * to agree with what is playing before its id is allowed to name a document.
 */
object AmllTtml {

    private const val TAG = "Lyrics"
    private const val SEARCH = "https://itunes.apple.com/search"
    private const val RAW =
        "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/am-lyrics/"
    private const val DURATION_TOLERANCE_SECONDS = 3.0
    private const val SEARCH_LIMIT = 10

    fun documentUrl(trackId: Long): String = "$RAW$trackId.ttml"

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        get: suspend (String) -> String? = { lyricsGet(it) },
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val hit = search(title, artist, durationMs)
        val id = hit?.trackId
        if (id == null) {
            TrackLog.i(TAG, "lyrics source ttml: miss id=unknown lines=0")
            return@withContext null
        }
        val lines = get(documentUrl(id))?.let(TtmlLyrics::parse)?.takeIf { it.isNotEmpty() }
        if (lines == null) {
            TrackLog.i(TAG, "lyrics source ttml: miss id=$id lines=0")
            return@withContext null
        }
        TrackLog.i(TAG, "lyrics source ttml: hit id=$id lines=${lines.size}")
        lines
    }

    private fun search(title: String, artist: String, durationMs: Long): Hit? {
        val url = SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("term", "$title $artist".trim())
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        val body = lyricsGet(url.toString()) ?: return null
        return best(parseSearch(body).orEmpty(), title, artist, durationMs)
    }

    internal fun parseSearch(body: String): List<Hit>? =
        runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()?.results

    /**
     * The hit that is this recording, or null. Same title (outside brackets and
     * a " - " suffix), same version words, a shared credited artist, and a
     * length within [DURATION_TOLERANCE_SECONDS]. Of those, the closest length
     * wins. A miss is recoverable; another song's words are not.
     */
    internal fun best(hits: List<Hit>, title: String, artist: String, durationMs: Long): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val seconds = durationMs / 1000.0

        fun distance(hit: Hit): Double =
            if (durationMs > 0 && hit.trackTimeMillis != null) {
                abs(hit.trackTimeMillis / 1000.0 - seconds)
            } else {
                0.0
            }

        return hits
            .filter { hit ->
                val name = hit.trackName ?: return@filter false
                hit.trackId != null &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(hit.artistName.orEmpty()).any { it in wantedArtists }) &&
                    distance(hit) <= DURATION_TOLERANCE_SECONDS
            }
            .minByOrNull(::distance)
    }

    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - "))
            .ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extras = BRACKETED.findAll(title).map { it.value }.toList() +
            title.substringAfter(" - ", "")
        return extras.flatMap { extra ->
            val words = normalized(extra).split(' ').filter { it.isNotEmpty() }
            if (words.joinToString("") in NEUTRAL) emptyList() else words.filter { it in VERSION_WORDS }
        }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Asides that read like a take but name the ordinary release. */
    private val NEUTRAL = setOf(
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack", "cleanedit", "cleanversion", "cleanmix", "clean",
    )

    /** Bracket words that make it a different recording, not a different label. */
    private val VERSION_WORDS = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
        "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
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
