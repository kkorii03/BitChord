package com.music.bitchord.data.spotify

import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/** Single track metadata extracted from a Spotify playlist. */
data class SpotifyImportTrack(
    val title: String,
    val artist: String,
    /** Runtime in milliseconds when Spotify stated one; 0 when it did not. */
    val durationMs: Int = 0,
    /** Release name when Spotify stated one, used to separate catalogue collisions. */
    val album: String? = null,
)

/** What [SpotifyImporter.resolveToSongs] matched, and how confident it was. */
data class SpotifyMatchResult(
    val songs: List<Song>,
    val unmatched: List<SpotifyImportTrack>,
    /** Tracks kept only because a search answered something, with no confident match. */
    val looselyMatched: Int,
)

/** One track's chosen candidate; [loose] when only the first search hit was available. */
internal data class SpotifyMatch(val song: Song?, val loose: Boolean)

object SpotifyImporter {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A Spotify playlist id is 22 base-62 characters. */
    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("spotify:playlist:")) {
            val id = trimmed.removePrefix("spotify:playlist:").substringBefore("?").substringBefore("/")
            return id.takeIf { PLAYLIST_ID.matches(it) }
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host?.lowercase().orEmpty())) return null
        val segments = uri.pathSegments.orEmpty()
        val playlistIdx = segments.indexOf("playlist")
        if (playlistIdx == -1 || playlistIdx + 1 >= segments.size) return null
        return segments[playlistIdx + 1].takeIf { PLAYLIST_ID.matches(it) }
    }

    /**
     * Fetches public Spotify playlist details from Spotify's embed endpoint.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<SpotifyImportTrack>> =
        withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val html = Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    error("Failed to load Spotify playlist (HTTP ${response.code})")
                }
                body
            }

            // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = scriptRegex.find(html)
                ?: error("Spotify 재생목록 정보를 읽을 수 없습니다. 공개 재생목록인지 확인하십시오.")

            val jsonString = match.groupValues[1]
            val root = json.parseToJsonElement(jsonString).jsonObject

            val props = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
            val stateData = props?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject
                ?: props?.get("entity")?.jsonObject
                ?: error("Spotify playlist entity missing or private")

            val playlistTitle = stateData["name"]?.jsonPrimitive?.content
                ?: stateData["title"]?.jsonPrimitive?.content
                ?: "가져온 Spotify 재생목록"

            val trackListJson = stateData["trackList"]?.jsonArray
                ?: stateData["tracks"]?.jsonArray
                ?: JsonArray(emptyList())

            val tracks = mutableListOf<SpotifyImportTrack>()
            for (element in trackListJson) {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content
                    ?: continue
                val subtitle = obj["subtitle"]?.jsonPrimitive?.content
                    ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                        it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    }
                    ?: ""
                if (title.isNotBlank()) {
                    tracks.add(
                        SpotifyImportTrack(
                            title = title.trim(),
                            artist = subtitle.trim(),
                            durationMs = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0,
                            album = obj["album"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }

            if (tracks.isEmpty()) {
                error("공개 Spotify 재생목록에서 트랙을 찾지 못했습니다.")
            }

            // The embed page stops at its first 100 tracks. The web player's
            // own query, with the anonymous token the embed hands out, pages
            // through the rest; if it refuses, the 100 already read stand.
            if (tracks.size >= EMBED_TRACK_LIMIT) {
                val token = props?.get("state")?.jsonObject
                    ?.get("settings")?.jsonObject
                    ?.get("session")?.jsonObject
                    ?.get("accessToken")?.jsonPrimitive?.content
                if (token != null) {
                    runCatching { fetchRemainingTracks(playlistId, token, tracks.size) }
                        .getOrNull()
                        ?.let { tracks.addAll(it) }
                }
            }

            Pair(playlistTitle, tracks)
        }

    /**
     * The YouTube Music song that is [track], or null if there is none: the
     * best candidate by title, artist, length and album, and failing that the
     * search's first hit.
     */
    suspend fun matchTrack(track: SpotifyTrack): Song? {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        return bestMatch(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                album = track.album,
            ),
        ).song
    }

    /**
     * The best candidate for a target, and whether it was only the search's
     * first hit because nothing scored.
     *
     * Pure, so the choice can be tested without a search: the importer used to
     * take that first hit unconditionally, which silently picked whichever
     * recording the backend happened to rank first.
     */
    internal fun bestMatch(candidates: List<Song>, target: TrackMatcher.Target): SpotifyMatch {
        TrackMatcher.best(candidates, target)?.let { return SpotifyMatch(it, loose = false) }
        val first = candidates.firstOrNull() ?: return SpotifyMatch(null, loose = false)
        return SpotifyMatch(first, loose = true)
    }

    /** Tracks the embed page lists before it cuts off. */
    private const val EMBED_TRACK_LIMIT = 100
    private const val PAGE_SIZE = 100
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FETCH_PLAYLIST_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"

    /** Tracks from [start] to the end of the playlist, a page at a time. */
    private fun fetchRemainingTracks(
        playlistId: String,
        token: String,
        start: Int,
    ): List<SpotifyImportTrack> {
        val out = mutableListOf<SpotifyImportTrack>()
        var offset = start
        while (true) {
            val variables = """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$PAGE_SIZE,"enableWatchFeedEntrypoint":false}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$FETCH_PLAYLIST_HASH"}}"""
            val url = PATHFINDER_URL.toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "fetchPlaylist")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer")
                .header("Accept", "application/json")
                .build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val content = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("playlistV2")?.jsonObject?.get("content")?.jsonObject
                ?: break
            val items = content["items"]?.jsonArray ?: break
            for (item in items) {
                val data = item.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject ?: continue
                val title = data["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artist = data["artists"]?.jsonObject?.get("items")?.jsonArray
                    ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                    ?.joinToString(", ")
                    .orEmpty()
                out.add(
                    SpotifyImportTrack(
                        title = title,
                        artist = artist,
                        durationMs = data["duration"]?.jsonObject
                            ?.get("totalMilliseconds")?.jsonPrimitive?.intOrNull ?: 0,
                        album = data["albumOfTrack"]?.jsonObject
                            ?.get("name")?.jsonPrimitive?.contentOrNull
                            ?.trim()?.takeIf { it.isNotBlank() },
                    ),
                )
            }
            val total = content["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: break
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /**
     * Resolves a list of Spotify tracks to full Song objects using Innertube search.
     * Reports real-time progress via [onProgress].
     */
    suspend fun resolveToSongs(
        tracks: List<SpotifyImportTrack>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): SpotifyMatchResult = coroutineScope {
        val total = tracks.size
        val completedCount = AtomicInteger(0)
        val looseCount = AtomicInteger(0)
        val semaphore = Semaphore(4) // Bounded concurrency for search queries

        val deferredResults = tracks.map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val query = "${track.title} ${track.artist}".trim()
                    val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()
                    val match = bestMatch(
                        candidates,
                        TrackMatcher.Target(
                            title = track.title,
                            artist = track.artist,
                            durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                            album = track.album,
                        ),
                    )
                    if (match.loose) looseCount.incrementAndGet()

                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)

                    Triple(match.song, if (match.song == null) track else null, match.loose)
                }
            }
        }

        val results = deferredResults.awaitAll()
        SpotifyMatchResult(
            songs = results.mapNotNull { it.first },
            unmatched = results.mapNotNull { it.second },
            looselyMatched = looseCount.get(),
        )
    }
}
