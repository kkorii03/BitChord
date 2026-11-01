package com.music.bitchord.data.innertube

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An anonymous www.youtube.com search, for the rows YouTube Music will not
 * give a Korean IP without Premium.
 *
 * Measured from a Korean IP (2026-10-18): music.youtube.com's search answers
 * the unfiltered page (24 rows) and albums (20), but the Songs filter comes
 * back with no rows at all, while www.youtube.com's video search returns the
 * same tracks — 17 of 20 from the artists' official "- Topic" channels for
 * "seven nation army topic". [YouTubeWebParser.parseHistory] already reads
 * those `videoRenderer` / `lockupViewModel` rows into songs with the music
 * ones (Topic channels, music badges) first.
 *
 * Signed out on purpose, like every other search in the app: no cookie jar,
 * no account visitor id, so nothing lands in the user's YouTube history.
 */
internal object YouTubeWebSearch {

    private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val WEB_VERSION = "2.20251015.01.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** YouTube's own "Videos" search filter. */
    private const val VIDEOS_FILTER = "EgIQAQ%3D%3D"

    /** YouTube's own "Playlists" search filter. */
    private const val PLAYLISTS_FILTER = "EgIQAw%3D%3D"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        Http.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Rows from an anonymous youtube.com search, or the failure that stopped
     * it. A failed fallback must stay distinguishable from a search that
     * genuinely matched nothing: callers used to fold both into an empty list,
     * which turned a broken connection into "no results".
     */
    fun songs(query: String, kind: SearchKind = SearchKind.TYPED): Result<List<Song>> =
        response(query, VIDEOS_FILTER, kind).map(YouTubeWebParser::parseHistory)

    /** Playlist cards for [query] from the playlists filter, anonymous like [songs]. */
    fun playlists(query: String, kind: SearchKind = SearchKind.TYPED): List<ShelfItem> =
        response(query, PLAYLISTS_FILTER, kind)
            .getOrNull()
            ?.let(YouTubeWebParser::parsePlaylistResults)
            .orEmpty()

    private fun response(query: String, params: String, kind: SearchKind): Result<JsonObject> {
        // Logged here too because this path builds its own request: no cookie
        // jar, no account visitor id, and never the query itself.
        DebugLog.d(
            LOG_TAG,
            Innertube.searchLogLine("www.youtube.com", "youtubei/v1/search", kind, query.length),
        )
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_VERSION)
                    put("hl", "ko")
                    put("gl", "KR")
                }
            }
            put("query", query)
            put("params", java.net.URLDecoder.decode(params, "UTF-8"))
        }
        val request = okhttp3.Request.Builder()
            .url(SEARCH_URL)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = try {
            client.newCall(request).execute().use { reply ->
                if (!reply.isSuccessful) {
                    TrackLog.w(TAG, "youtube search HTTP ${reply.code}")
                    return Result.failure(IOException("youtube search HTTP ${reply.code}"))
                }
                reply.body?.string()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A transport or read failure here is a failed fallback, not an
            // empty result: the caller needs to be able to offer a retry.
            TrackLog.w(TAG, "youtube search failed: ${failure.message}")
            return Result.failure(failure)
        } ?: return Result.failure(IOException("youtube search returned no body"))
        return runCatching { json.parseToJsonElement(text) as? JsonObject }
            .fold(
                onSuccess = { root ->
                    if (root == null) {
                        Result.failure(IOException("youtube search body was not an object"))
                    } else {
                        Result.success(root)
                    }
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    TrackLog.w(TAG, "youtube search parse failed: ${failure.message}")
                    Result.failure(failure)
                },
            )
    }

    /** Reads a search response; a shape surprise is no rows, never a thrown page. */
    internal fun parseResponse(text: String): List<Song> =
        parse(text, YouTubeWebParser::parseHistory)

    /** Reads a playlists-filter response the same forgiving way. */
    internal fun parsePlaylistResponse(text: String): List<ShelfItem> =
        parse(text, YouTubeWebParser::parsePlaylistResults)

    private fun <T> parse(text: String, read: (JsonObject) -> List<T>): List<T> =
        runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching emptyList()
            read(root)
        }.onFailure {
            if (it is CancellationException) throw it
            TrackLog.w(TAG, "youtube search parse failed: ${it.message}")
        }.getOrDefault(emptyList())

    private const val TAG = "YouTubeWebSearch"

    /** Search-privacy lines go to the same logcat tag the rest of Innertube uses. */
    private const val LOG_TAG = "BitChord"
}
