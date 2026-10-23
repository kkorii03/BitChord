package com.music.bitchord.data.lyrics

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.TrackMatcher
import java.text.Normalizer
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Which catalogue recording a video is a cover of, from search results.
 *
 * The video's timing is measured against the record, so this has to find the
 * record — and the names it has to go on rarely match a catalogue row letter
 * for letter. A cover's title carries a translation in brackets ("ダンスホール
 * (Dance Hall, 댄스홀)"); the anonymous search answers in English, so a Korean
 * song comes back as "The Western Sky (서쪽 하늘)" by "Lee Seung Chul"; a
 * Japanese one as "晴る - Sunny" by "Yorushika". A match on the exact title and
 * artist, which is what the lookup used, missed most of a cover singer's
 * playlist.
 *
 * So each name is split into every reading it carries — the main title, each
 * bracketed or dash-separated alternative — and folded (case, width, spaces,
 * punctuation) before comparing. A row is ranked by how well its readings
 * meet the query's, whether an artist agrees, how close its length is to the
 * video's, and pushed down when it is a version the query didn't ask for
 * (instrumental, live, karaoke, a remix). The measurement then tries the best
 * one or two and keeps the first that actually lines up with the video, so a
 * wrong guess costs a download, not a wrong timing.
 */
object RecordingPick {

    /**
     * Searches for the record behind a video known by [names] — (title,
     * artist) pairs, most trusted first: the name its lyrics were found
     * under, the cover's own reading of it, other readings — and returns the
     * best [max] candidates. Anonymous searches, kept out of the listener's
     * history: the first name in the US and the Korean catalogue (one
     * answers in the release's own script, the other with Korean names for
     * Korean songs), the second in the US one.
     */
    suspend fun find(names: List<Pair<String, String>>, videoMs: Long, max: Int = 2): List<Song> = withContext(Dispatchers.IO) {
        val clean = names
            .map { (t, a) -> t.forLyricsSearch() to a.artistForLyricsSearch() }
            .filter { it.first.isNotBlank() }
            .distinct()
        if (clean.isEmpty()) return@withContext emptyList()
        // (name, catalogue, main reading only)
        data class Ask(val name: Pair<String, String>, val hl: String, val gl: String, val main: Boolean)
        val asks = buildList {
            add(Ask(clean[0], "en", "US", main = true))
            add(Ask(clean[0], "ko", "KR", main = true))
            // As written too: the bracketed reading sometimes is the one the
            // catalogue knows ("がらくた(잡동사니)" finds Yonezu, "がらくた" alone a namesake).
            if (BRACKETS.containsMatchIn(clean[0].first)) add(Ask(clean[0], "en", "US", main = false))
            clean.getOrNull(1)?.let { add(Ask(it, "en", "US", main = true)) }
        }
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val rows = coroutineScope {
            asks.map { ask ->
                async {
                    runCatching<List<Song>> {
                        val (name, hl, gl) = Triple(ask.name, ask.hl, ask.gl)
                        // The main reading: a translation in brackets mostly
                        // throws the search off (see the as-written ask above).
                        val title = if (!ask.main) name.first else {
                            BRACKETS.replace(name.first, " ").split(" - ", " / ").first().trim().ifBlank { name.first }
                        }
                        val response = Innertube.searchAnonymous(
                            query = "$title ${name.second}".trim(),
                            params = SearchFilter.SONGS.params,
                            hl = hl,
                            gl = gl,
                            kind = SearchKind.COVER,
                        )
                        InnertubeParser.parseSearchPage(response, includeVideos = false).rows
                            .filterIsInstance<SearchResult.Track>()
                            .map { it.song }
                            .take(5)
                    }.getOrElse {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        failed.incrementAndGet()
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        // Not one search got an answer — offline, or the catalogue erroring:
        // that is no verdict on the song, and must not be kept as "no original".
        if (failed.get() == asks.size) throw java.io.IOException("every search for the original failed")
        // Interleaved, so each search's own first answer keeps its standing.
        val merged = buildList {
            for (i in 0 until 5) rows.forEach { list -> list.getOrNull(i)?.let(::add) }
        }
        rank(names.map { it.first } + clean.map { it.first }, names.map { it.second } + clean.map { it.second }, merged, videoMs, max)
    }

    /** The best [max] rows worth measuring against, best first. */
    fun rank(
        titles: List<String>,
        artists: List<String>,
        rows: List<Song>,
        videoMs: Long,
        max: Int = 2,
    ): List<Song> {
        val wantedTitles = titles.flatMap(::readings).toSet()
        val wantedArtists = artists.flatMap(::artistReadings).toSet()
        val askedVersion = titles.any { versionWord(it) }
        val seed = titles.firstOrNull()?.let { title ->
            Song(
                videoId = "",
                title = title,
                artist = artists.firstOrNull().orEmpty(),
                thumbnailUrl = null,
            )
        }
        fun sameRecordingAsAny(row: Song): Boolean = titles.indices.any { index ->
            TrackMatcher.sameRecording(
                row,
                TrackMatcher.Target(
                    title = titles[index],
                    artist = artists.getOrNull(index).orEmpty(),
                    durationSec = TrackMatcher.secondsOf(row.durationText),
                    isVideo = true,
                ),
            )
        }
        fun artistMatchesAny(row: Song): Boolean = artists.any { artist ->
            artist.isNotBlank() && row.artist.isNotBlank() && TrackMatcher.sharesArtist(artist, row.artist)
        }
        // "i hate u, i love u" and "I Hate You, I Love You" are one title written
        // two ways; "Lost in the Fire" and "The Hills" are not, even though the
        // same artist sings both. A shared title core is the strict rule; this
        // catches only spelling variants.
        fun closeTitle(row: Song): Boolean = titles.any { title ->
            titleSimilarity(title, row.title) >= TITLE_SIMILARITY
        }
        // The one cross-script case the readings cannot express: a Korean or
        // Japanese title answered by a catalogue row filed only in English.
        // It is allowed for the leading answer and a comparable length, never
        // for a second title in the same script — which is how "The Hills"
        // pulled in The Weeknd's unrelated "Lost in the Fire".
        fun crossScriptTranslation(row: Song, order: Int): Boolean {
            if (order != 0) return false
            val wanted = titles.firstOrNull() ?: return false
            val otherScript = TrackMatcher.hasOtherScript(wanted) && TrackMatcher.hasLatinLetters(row.title) ||
                TrackMatcher.hasOtherScript(row.title) && TrackMatcher.hasLatinLetters(wanted)
            if (!otherScript) return false
            val ms = row.durationMillis()
            val length = if (ms <= 0L || videoMs <= 0L) 0L else abs(ms - videoMs)
            return videoMs <= 0L || ms <= 0L || length <= 90_000L
        }
        val scored = rows
            .asSequence()
            .filter { !it.isVideo }
            .filter { row -> seed == null || !TrackMatcher.isCoverArrangement(row, seed) }
            // A take the query did not ask for is not the reference record.
            .filter { row -> askedVersion || !versionWord(row.title) }
            .distinctBy { it.videoId }
            .mapIndexedNotNull { order, row ->
                val sameSong = sameRecordingAsAny(row) ||
                    (artistMatchesAny(row) && closeTitle(row))
                if (!sameSong && !crossScriptTranslation(row, order)) return@mapIndexedNotNull null
                score(row, order, wantedTitles, wantedArtists, askedVersion, videoMs)?.let { row to it }
            }
            .toList()
        // When some row is by the artist asked for, a namesake by someone else
        // is not the record however exactly its title matches: "I Hate You I
        // Love You" by a stranger outranked gnash's "i hate u, i love u", and
        // only two candidates are measured.
        val artistSeen = scored.any { (row, _) -> artistMatches(row, wantedArtists) }
        return scored
            .map { (row, score) -> row to if (artistSeen && !artistMatches(row, wantedArtists)) score - OTHER_ARTIST_PENALTY else score }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(max)
    }

    private fun artistMatches(row: Song, artists: Set<String>): Boolean {
        val rowArtists = artistReadings(row.artist)
        return rowArtists.any { it in artists } || rowArtists.any { r -> artists.any { a -> contains(r, a) } }
    }

    private const val OTHER_ARTIST_PENALTY = 100

    private fun score(
        row: Song,
        order: Int,
        titles: Set<String>,
        artists: Set<String>,
        askedVersion: Boolean,
        videoMs: Long,
    ): Int? {
        val rowTitles = readings(row.title)
        val title = when {
            rowTitles.any { it in titles } -> 100
            rowTitles.any { r -> titles.any { t -> contains(r, t) } } -> 60
            else -> 0
        }
        val rowArtists = artistReadings(row.artist)
        val artist = when {
            rowArtists.any { it in artists } -> 40
            rowArtists.any { r -> artists.any { a -> contains(r, a) } } -> 25
            else -> 0
        }
        val ms = row.durationMillis()
        val length = if (ms <= 0L || videoMs <= 0L) 0 else when (abs(ms - videoMs)) {
            in 0L..20_000L -> 25
            in 20_001L..45_000L -> 12
            in 45_001L..90_000L -> 0
            else -> -30
        }
        // A name in another script than anything asked (an English title for a
        // Korean song) can still be the record: the top answer of a search for
        // exactly this song, about as long as the video. Measuring decides.
        val firstAndClose = order == 0 && length >= 12
        if (title == 0 && artist == 0 && !firstAndClose) return null
        if (title == 0 && !firstAndClose && artist < 40) return null
        val version = if (!askedVersion && versionWord(row.title)) -80 else 0
        return title + artist + length + version - order * 3 + if (firstAndClose && title == 0) 30 else 0
    }

    /** Every name [title] goes by, folded: "A (B, C) - D" → A, B, C, D, and the whole. */
    internal fun readings(title: String): Set<String> {
        val out = LinkedHashSet<String>()
        fun add(part: String) {
            val f = fold(part.replace(FEAT, ""))
            if (f.length >= 2 || (f.isNotEmpty() && f.any { it.code > 0x2E80 })) out += f
        }
        add(title)
        val bracketed = BRACKETS.findAll(title).map { it.groupValues[1] }.toList()
        val outside = BRACKETS.replace(title, " ")
        outside.split(" - ", " – ", " — ", " / ", "_").forEach(::add)
        bracketed.forEach { inner -> inner.split(',', '/', '、', ';').forEach(::add) }
        return out
    }

    private fun artistReadings(artist: String): Set<String> =
        artist.split(',', '&', '、', '/', '×', ';')
            .flatMap { it.split(" x ", " X ", " feat. ", " ft. ", " and ", " with ") }
            .flatMap(::readings)
            .toSet()

    /** Case, width and accents folded, spaces and punctuation gone. */
    internal fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .filter { it.isLetterOrDigit() }

    /**
     * How alike two titles are after folding, 0…1.0. Used only to admit spelling
     * variants ("u" for "you") beside an agreeing artist; a different song by
     * the same artist stays well under [TITLE_SIMILARITY].
     */
    private fun titleSimilarity(a: String, b: String): Double {
        val left = TrackMatcher.parseTitle(a).core
        val right = TrackMatcher.parseTitle(b).core
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val longest = maxOf(left.length, right.length)
        return 1.0 - levenshtein(left, right).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** Titles at least this alike are the same one written differently. */
    private const val TITLE_SIMILARITY = 0.72

    private fun contains(a: String, b: String): Boolean {
        val (long, short) = if (a.length >= b.length) a to b else b to a
        val enough = if (short.any { it.code > 0x2E80 }) 2 else 4
        return short.length >= enough && long.contains(short)
    }

    private fun versionWord(title: String): Boolean = VERSION.containsMatchIn(title)

    private val BRACKETS = Regex("""[(\[（【「『]([^)\]）】」』]*)[)\]）】」』]""")
    private val FEAT = Regex("""(?i)\b(feat|ft)\.?\s.*$""")
    private val VERSION = Regex(
        """(?i)instrumental|\binst\b|karaoke|off\s*vocal|\bmr\b|\blive\b|remix|acoustic|piano|orchestra ver|cover|sped ?up|slowed|nightcore|8d|backing|伴奏|カラオケ|라이브|반주""",
    )
}
