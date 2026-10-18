package com.music.bitchord.data.innertube

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        Http.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun songs(query: String): List<Song> {
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
            put("params", java.net.URLDecoder.decode(VIDEOS_FILTER, "UTF-8"))
        }
        val request = okhttp3.Request.Builder()
            .url(SEARCH_URL)
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TrackLog.w(TAG, "youtube search HTTP ${response.code}")
                    return emptyList()
                }
                response.body?.string()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Search is a fallback for a filter Music refused; a transport or
            // read failure here is "no result", not a failed search page.
            TrackLog.w(TAG, "youtube search failed: ${failure.message}")
            return emptyList()
        } ?: return emptyList()
        return parseResponse(text)
    }

    /** Reads a search response; a shape surprise is no rows, never a thrown page. */
    internal fun parseResponse(text: String): List<Song> =
        runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching emptyList()
            YouTubeWebParser.parseHistory(root)
        }.onFailure {
            if (it is CancellationException) throw it
            TrackLog.w(TAG, "youtube search parse failed: ${it.message}")
        }.getOrDefault(emptyList())

    private const val TAG = "YouTubeWebSearch"
}
