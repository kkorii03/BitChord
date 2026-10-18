package com.music.bitchord.data.charts

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.ShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The three chart lists `charts.youtube.com` still serves without Premium. */
internal enum class ChartType(
    val responseKey: String,
    val queryValue: String,
    val shelfTitle: String,
) {
    TRACKS("trackViews", "TRACKS", "인기곡"),
    VIDEOS("videoViews", "VIDEOS", "인기 뮤직비디오"),
    ARTISTS("artistViews", "ARTISTS", "인기 아티스트"),
}

internal data class ChartEntry(
    val title: String,
    val subtitle: String,
    val videoId: String? = null,
    val browseId: String? = null,
    val thumbnailUrl: String? = null,
) {
    fun shelfItem(): ShelfItem = ShelfItem(
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId,
        browseId = browseId,
    )
}

/**
 * The anonymous chart endpoint behind the gated Music charts page.
 *
 * `FEmusic_charts` on music.youtube.com is Premium-gated in Korea, but the
 * public charts site still answers `FEmusic_analytics_charts_home` with 100
 * rows per list. This is deliberately a separate client from [Http]'s normal
 * Innertube use: no cookie jar, a short ceiling, and the desktop analytics
 * client identity measured to work from an anonymous Korean IP.
 */
internal object ChartsApi {

    private const val TAG = "BitChord"
    private const val URL = "https://charts.youtube.com/youtubei/v1/browse?alt=json"
    private const val ORIGIN = "https://charts.youtube.com"
    private const val CLIENT_NAME = "WEB_MUSIC_ANALYTICS"
    private const val CLIENT_VERSION = "2.0"
    private const val LANGUAGE = "ko"
    private const val REGION = "KR"
    private const val CACHE_MS = 6L * 60 * 60 * 1000
    private const val DEFAULT_COUNTRY = "kr"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** Country codes shown by the existing charts picker; `ZZ` is the page's global choice. */
    val countries: List<String> = listOf("KR", "US", "JP", "GB", "ZZ")

    /** The country used when the caller has none or names one the endpoint does not know. */
    @Volatile
    var defaultCountry: String = DEFAULT_COUNTRY

    private data class Cached(val at: Long, val entries: List<ChartEntry>)

    private val cache = ConcurrentHashMap<String, Cached>()

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        Http.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The request-side country code: ISO two-letter in lower case, `global` for
     * the global chart, and the configured default for anything else.
     */
    fun requestCountry(country: String?): String {
        val code = country?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            code.isEmpty() -> fallbackCountry()
            code == "zz" || code == "global" -> "global"
            code.length == 2 && code.all { it in 'a'..'z' } -> code
            else -> fallbackCountry()
        }
    }

    /** The configured default, normalised the same way, or `kr` if it is not a usable code. */
    private fun fallbackCountry(): String {
        val code = defaultCountry.trim().lowercase(Locale.ROOT)
        return when {
            code == "global" || code == "zz" -> "global"
            code.length == 2 && code.all { it in 'a'..'z' } -> code
            else -> DEFAULT_COUNTRY
        }
    }

    /** The display-side code the charts picker already expects: upper case, `ZZ` for global. */
    fun displayCountry(country: String?): String {
        val request = requestCountry(country)
        return if (request == "global") "ZZ" else request.uppercase(Locale.ROOT)
    }

    /**
     * One chart list, cached per request country and type for six hours. A
     * failed request returns an empty list without poisoning the cache.
     */
    suspend fun entries(country: String?, type: ChartType): List<ChartEntry> {
        val code = requestCountry(country)
        val key = "$code:${type.name}"
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, entries) ->
            if (now - at < CACHE_MS) return entries
        }
        val fetched = try {
            withContext(Dispatchers.IO) { fetch(code, type) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "charts $code/${type.queryValue} failed: ${failure.message}")
            return emptyList()
        } ?: return emptyList()
        cache[key] = Cached(now, fetched)
        return fetched
    }

    private fun fetch(country: String, type: ChartType): List<ChartEntry>? {
        val query = "perspective=CHART_DETAILS" +
            "&chart_params_country_code=$country" +
            "&chart_params_chart_type=${type.queryValue}" +
            "&chart_params_period_type=WEEKLY"
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", LANGUAGE)
                    put("gl", REGION)
                    put("theme", "MUSIC")
                }
            }
            put("browseId", "FEmusic_analytics_charts_home")
            put("query", query)
        }
        val request = okhttp3.Request.Builder()
            .url(URL)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "charts $country/${type.queryValue} HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            ChartsParser.parse(json.parseToJsonElement(text), type)
        }
    }
}

/**
 * Reads `trackViews` / `videoViews` / `artistViews` out of a charts response.
 *
 * The exact field names inside each entry were not measured, so every field is
 * looked up by key name anywhere in the entry rather than by a fixed path.
 * An entry whose title cannot be found is dropped, and its keys are logged once
 * so the next response can be shaped against reality rather than a guess.
 */
internal object ChartsParser {

    private const val TAG = "BitChord"
    private const val NO_TITLE = ""

    private val missingTitleLogged = AtomicBoolean(false)

    fun parse(root: JsonElement, type: ChartType): List<ChartEntry> {
        val rows = findArray(root, type.responseKey) ?: return emptyList()
        return rows.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val title = titleOf(entry)
            if (title == NO_TITLE) {
                if (missingTitleLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "charts ${type.responseKey} entry has no title; keys=${entry.keys}")
                }
                return@mapNotNull null
            }
            ChartEntry(
                title = title,
                subtitle = subtitleOf(entry, type),
                videoId = when (type) {
                    ChartType.ARTISTS -> null
                    // Measured 2026-10-19: a track carries its music video as
                    // encryptedVideoId and the album audio as atvExternalVideoId
                    // (YouTube Music's "song", square art); a video entry's id
                    // is the video id itself.
                    ChartType.TRACKS -> entry.string("atvExternalVideoId") ?: videoIdOf(entry)
                    else -> videoIdOf(entry) ?: entry.string("id")
                },
                browseId = if (type == ChartType.ARTISTS) browseIdOf(entry) else null,
                thumbnailUrl = thumbnailOf(entry),
            )
        }
    }

    private fun titleOf(entry: JsonObject): String =
        (
            entry.string("name")
                ?: entry.string("title")
                ?: entry.findString("name")
                ?: entry.findString("title")
            )?.takeIf { it.isNotBlank() } ?: NO_TITLE

    private fun videoIdOf(entry: JsonObject): String? =
        entry.string("encryptedVideoId")
            ?: entry.string("videoId")
            ?: entry.findString("encryptedVideoId")
            ?: entry.findString("videoId")

    private fun browseIdOf(entry: JsonObject): String? =
        entry.string("browseId")
            ?: entry.string("externalChannelId")
            ?: entry.string("channelId")
            ?: entry.findString("browseId")
            ?: entry.findString("channelId")

    private fun subtitleOf(entry: JsonObject, type: ChartType): String =
        if (type == ChartType.ARTISTS) {
            entry.string("subtitle")
                ?: entry.findString("subtitle")
                ?: artistsOf(entry)
        } else {
            artistsOf(entry)
        }

    private fun artistsOf(entry: JsonObject): String {
        val artists = entry["artists"]
            ?: entry["artist"]
            ?: entry.findElement("artists")
            ?: entry.findElement("artist")
        val names = artists?.let(::artistNames).orEmpty()
        if (names.isNotBlank()) return names
        return entry.string("artistName") ?: entry.findString("artistName") ?: ""
    }

    private fun artistNames(element: JsonElement): String = when (element) {
        is JsonArray -> element.mapNotNull { artistNames(it).takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(", ")
        is JsonObject -> element.string("name")
            ?: element.string("title")
            ?: element.findString("name")
            ?: ""
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }

    private fun thumbnailOf(entry: JsonObject): String? {
        THUMBNAIL_KEYS.forEach { key ->
            val holder = entry[key] ?: entry.findElement(key) ?: return@forEach
            urlsIn(holder).lastOrNull()?.let { return it }
        }
        return entry.findString("url")?.takeIf { it.startsWith("http") }
    }

    private fun urlsIn(element: JsonElement): List<String> = when (element) {
        is JsonObject -> buildList {
            (element["url"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.startsWith("http") }
                ?.let(::add)
            element.values.forEach { addAll(urlsIn(it)) }
        }
        is JsonArray -> element.flatMap(::urlsIn)
        is JsonPrimitive -> listOfNotNull(
            element.contentOrNull?.takeIf { it.startsWith("http") },
        )
        else -> emptyList()
    }

    private fun findArray(element: JsonElement, key: String): JsonArray? = when (element) {
        is JsonObject -> (element[key] as? JsonArray)
            ?: element.values.firstNotNullOfOrNull { findArray(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findArray(it, key) }
        else -> null
    }

    private fun JsonElement.findElement(key: String): JsonElement? = when (this) {
        is JsonObject -> this[key]
            ?: values.firstNotNullOfOrNull { it.findElement(key) }
        is JsonArray -> firstNotNullOfOrNull { it.findElement(key) }
        else -> null
    }

    private fun JsonElement.findString(key: String): String? = when (this) {
        is JsonObject -> {
            (this[key] as? JsonPrimitive)?.contentOrNull
                ?: values.firstNotNullOfOrNull { it.findString(key) }
        }
        is JsonArray -> firstNotNullOfOrNull { it.findString(key) }
        else -> null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private val THUMBNAIL_KEYS = listOf(
        "thumbnail",
        "thumbnails",
        "image",
        "images",
        "cover",
        "artwork",
    )
}
