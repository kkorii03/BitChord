package com.music.bitchord.data.cover

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.StoredVerdictCache
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.innertube.SearchKind
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Finds a square album cover for a youtube.com fallback row.
 *
 * The youtube.com pages only carry video thumbnails (`i.ytimg.com`, a 16:9
 * frame), which is not what YouTube Music shows for the same recording. Both
 * Apple's public iTunes search API and Deezer's search API expose square album
 * artwork without a key, so the song is looked up by its cleaned title and
 * artist. A result is accepted only when it is demonstrably the same
 * recording: the title must agree after the same parsing [TrackMatcher] uses
 * for catalogue playback, and a credited artist must be shared. Anything else
 * leaves the YouTube thumbnail in place, which the UI already crops to a
 * centred square rather than letterboxing.
 *
 * Lookups are lazy ??only the rows a shelf is about to show are resolved ??and
 * a settled answer, including "no cover", is cached persistently. Nothing here
 * polls or schedules work later.
 */
internal object YouTubeCoverResolver {

    /** Requests in flight at once; the history/recent pages have at most four. */
    const val MAX_CONCURRENT = 4

    const val MAX_ENTRIES = 2_000

    private const val STORE_KEY = "youtube_cover_cache_v1"
    private const val NONE = "none"
    /** Unit separator: not legal in a URL and never in a catalogue title. */
    private const val FIELD = "\u001F"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private const val LOG_TAG = "BitChord"

    private val cache = StoredVerdictCache(STORE_KEY, MAX_ENTRIES)

    /** One in-flight lookup per song, so duplicate enrich callers share it. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Match?>>()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The app's shared OkHttp client, with its own short ceiling. The cover
     * lookup is cosmetic and must never hold a shelf for the ordinary 30-second
     * stream timeout.
     */
    private val client by lazy {
        Http.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    data class Candidate(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val album: String? = null,
    )

    /**
     * The catalogue's own metadata for a row whose recording was confirmed.
     *
     * [artworkUrl] is the square cover; [title] and [artist] are the names the
     * store files the same recording under ("Friday (feat. Jang Yi-jeong)" /
     * "IU"), which read better than an upload's packaging. Empty strings mean
     * an older cache entry kept only the artwork.
     */
    data class Match(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
    )

    /** Whether [url] is a 16:9 video frame that a square cover could replace. */
    fun isVideoThumbnail(url: String?): Boolean {
        val value = url ?: return false
        return value.contains("i.ytimg.com/") ||
            value.contains("img.youtube.com/vi/") ||
            value.contains("ytimg.com/vi/")
    }

    /**
     * The square cover for [song], or null when the row should keep its
     * thumbnail. Never throws: a network failure is reported as no answer.
     */
    suspend fun coverFor(song: Song): String? = matchFor(song)?.artworkUrl

    /**
     * The catalogue metadata for [song] when iTunes or Deezer has the same
     * recording, or null when neither could confirm it. A confirmed match
     * carries the store's title and artist as well as its square artwork, so
     * callers can show the catalogue's names while keeping the video id.
     *
     * Settled answers, including "no match", are cached; entries written by
     * the artwork-only version of this cache are still read as a cover with no
     * display metadata.
     */
    suspend fun matchFor(song: Song): Match? {
        if (!isVideoThumbnail(song.thumbnailUrl)) return null
        val target = TrackMatcher.targetOf(song)
        val artist = cleanArtist(target.artist)
        if (target.title.isBlank() || artist.isBlank()) return null
        val key = cacheKey(target.title, artist)

        cache.ensureLoaded(MusicGateFallback.sharedStore())
        cache.get(key)?.let { entry -> return decode(entry.value) }

        // Two enrich passes can ask for the same row in the same instant (the
        // detail page's cover fill and the search fallback's). Both used to
        // send the same iTunes/Deezer request; the first caller now owns the
        // lookup and the second awaits its answer.
        val mine = CompletableDeferred<Match?>()
        inFlight.putIfAbsent(key, mine)?.let { pending ->
            return try {
                pending.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
        try {
            val match = matchForUncached(target, artist, key)
            mine.complete(match)
            return match
        } catch (failure: Throwable) {
            mine.completeExceptionally(failure)
            throw failure
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private suspend fun matchForUncached(
        target: TrackMatcher.Target,
        artist: String,
        key: String,
    ): Match? {
        val query = TrackMatcher.queries(
            TrackMatcher.Target(title = target.title, artist = artist),
        ).firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val itunes = searchItunes(query)
        itunes?.firstNotNullOfOrNull { candidate ->
            candidate.takeIf {
                it.artworkUrl != null &&
                    accept(target.title, artist, it.title, it.artist)
            }
        }?.let { candidate ->
            return remember(
                key = key,
                candidate = candidate,
                fallbackTitle = target.title,
                fallbackArtist = artist,
            )
        }

        val deezer = searchDeezer(query)
        deezer?.firstNotNullOfOrNull { candidate ->
            candidate.takeIf {
                it.artworkUrl != null &&
                    accept(target.title, artist, it.title, it.artist)
            }
        }?.let { candidate ->
            return remember(
                key = key,
                candidate = candidate,
                fallbackTitle = target.title,
                fallbackArtist = artist,
            )
        }

        // Both services answered and neither had the recording: settled, worth
        // remembering. One failing is not an answer, so nothing is cached and a
        // later look can still find it.
        if (itunes != null && deezer != null) cache.put(key, NONE)
        return null
    }

    /**
     * Rewrites the thumbnail, title and artist of the first [limit] songs that
     * carry a video frame, using the catalogue's metadata where it confirmed
     * the recording. Order is preserved, and every row is returned whether or
     * not it found a match.
     */
    suspend fun enrich(songs: List<Song>, limit: Int = 30): List<Song> {
        if (songs.isEmpty() || limit <= 0) return songs
        val gate = Semaphore(MAX_CONCURRENT)
        return coroutineScope {
            songs.mapIndexed { index, song ->
                if (index >= limit || !isVideoThumbnail(song.thumbnailUrl)) {
                    async { song }
                } else {
                    async {
                        val match = try {
                            gate.withPermit { matchFor(song) }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            null
                        }
                        match?.let {
                            // A cover's title and byline name its own singer.
                            // The catalogue match may still supply the square
                            // artwork, but never replaces that credit.
                            val keepCredit = CoverTitles.isCover(song)
                            song.copy(
                                title = if (keepCredit) song.title
                                else it.title.ifBlank { song.title },
                                artist = if (keepCredit) song.artist
                                else it.artist.ifBlank { song.artist },
                                thumbnailUrl = it.artworkUrl ?: song.thumbnailUrl,
                            )
                        } ?: song
                    }
                }
            }.awaitAll()
        }
    }

    private fun remember(
        key: String,
        candidate: Candidate,
        fallbackTitle: String,
        fallbackArtist: String,
    ): Match {
        val match = Match(
            title = candidate.title.trim().ifBlank { fallbackTitle },
            artist = candidate.artist.trim().ifBlank { fallbackArtist },
            artworkUrl = candidate.artworkUrl,
        )
        cache.put(key, encode(match))
        return match
    }

    private fun encode(match: Match): String =
        listOf(match.artworkUrl.orEmpty(), match.title, match.artist).joinToString(FIELD)

    private fun decode(value: String): Match? {
        if (value == NONE) return null
        val parts = value.split(FIELD)
        if (parts.size >= 3) {
            return Match(
                title = parts[1],
                artist = parts[2],
                artworkUrl = parts[0].takeIf { it.startsWith("http") },
            )
        }
        // An entry from the artwork-only cache: still a cover, no display names.
        return value.takeIf { it.startsWith("http") }
            ?.let { Match(title = "", artist = "", artworkUrl = it) }
    }

    /**
     * Whether [candidateTitle] / [candidateArtist] describe the recording named
     * by [wantedTitle] / [wantedArtist].
     *
     * Deliberately stricter than a search result's first row: title cores and
     * version markers must agree exactly (after [TrackMatcher]'s packaging
     * removal), and an artist has to be shared. A cover, a live take or a remix
     * that happens to read similarly is refused rather than shown under the
     * song's name.
     */
    internal fun accept(
        wantedTitle: String,
        wantedArtist: String,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean {
        val wanted = TrackMatcher.parseTitle(wantedTitle, wantedArtist)
        val got = TrackMatcher.parseTitle(candidateTitle, candidateArtist)
        if (wanted.core.isBlank() || got.core.isBlank()) return false
        val sameTitle = wanted.core == got.core ||
            (wanted.core.length > 3 && got.core.length > 3 &&
                TrackMatcher.romajiOf(wanted.core) == TrackMatcher.romajiOf(got.core))
        if (!sameTitle) return false
        if (wanted.versions != got.versions) return false
        return TrackMatcher.sharesArtist(cleanArtist(wantedArtist), candidateArtist)
    }

    /** "Artist - Topic", "ArtistVEVO" and a byline's extra fields are not the credit. */
    internal fun cleanArtist(raw: String): String {
        var value = raw.substringBefore(" · ").trim()
        value = value.replace(Regex("""(?i)\s*-\s*topic\s*$"""), "")
        value = value.replace(Regex("""(?i)\s*vevo\s*$"""), "")
        return value.trim()
    }

    /** iTunes answers 100px art; the same asset is served at 600px by URL. */
    internal fun squareArtwork(url: String?): String? =
        url?.replace("100x100bb", "600x600bb")?.trim()?.takeIf { it.isNotBlank() }

    private fun cacheKey(title: String, artist: String): String {
        val parts = TrackMatcher.parseTitle(title, artist)
        val core = parts.core.ifBlank { TrackMatcher.normalize(title).trim() }
        val versions = parts.versions.sorted().joinToString(",")
        return "$core|$versions|" + TrackMatcher.normalize(cleanArtist(artist)).trim()
    }

    private suspend fun searchItunes(query: String): List<Candidate>? =
        withContext(Dispatchers.IO) {
            DebugLog.d(
                LOG_TAG,
                Innertube.searchLogLine(
                    "itunes.apple.com",
                    "search",
                    SearchKind.COVER,
                    query.length,
                ),
            )
            val url = "https://itunes.apple.com/search?term=" +
                URLEncoder.encode(query, "UTF-8") +
                "&entity=song&limit=5&country=KR"
            val body = get(url) ?: return@withContext null
            runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val results = root["results"] as? JsonArray ?: return@runCatching emptyList<Candidate>()
                results.mapNotNull { row ->
                    val objectRow = row as? JsonObject ?: return@mapNotNull null
                    Candidate(
                        title = objectRow.text("trackName").orEmpty(),
                        artist = objectRow.text("artistName").orEmpty(),
                        artworkUrl = squareArtwork(objectRow.text("artworkUrl100")),
                        album = objectRow.text("collectionName"),
                    )
                }
            }.getOrNull()
        }

    private suspend fun searchDeezer(query: String): List<Candidate>? =
        withContext(Dispatchers.IO) {
            DebugLog.d(
                LOG_TAG,
                Innertube.searchLogLine(
                    "api.deezer.com",
                    "search",
                    SearchKind.COVER,
                    query.length,
                ),
            )
            val url = "https://api.deezer.com/search?q=" +
                URLEncoder.encode(query, "UTF-8") +
                "&limit=5"
            val body = get(url) ?: return@withContext null
            runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"] as? JsonArray ?: return@runCatching emptyList<Candidate>()
                data.mapNotNull { row ->
                    val objectRow = row as? JsonObject ?: return@mapNotNull null
                    val album = objectRow["album"] as? JsonObject
                    Candidate(
                        title = objectRow.text("title").orEmpty(),
                        artist = (objectRow["artist"] as? JsonObject)?.text("name").orEmpty(),
                        artworkUrl = (album?.text("cover_xl") ?: album?.text("cover_big"))
                            ?.takeIf { it.startsWith("http") },
                        album = album?.text("title"),
                    )
                }
            }.getOrNull()
        }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    internal fun cacheSizeForTest(): Int = cache.size()

    internal fun cachePutForTest(key: String, value: String, at: Long = 0L) {
        cache.put(key, value, at)
    }

    internal fun cacheKeyForTest(title: String, artist: String): String = cacheKey(title, artist)

    internal fun cacheValueForTest(key: String): String? =
        cache.get(key)?.value

    @Synchronized
    internal fun resetForTest(store: MusicGateFallback.Store? = null) {
        cache.resetForTest()
        store?.let { cache.ensureLoaded(it) }
    }
}
