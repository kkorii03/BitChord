package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Shared plumbing for the lyric providers.
 *
 * They are all raced against each other by [LyricsRepository], so a provider
 * that hangs holds up the whole lookup. [LYRICS_TIMEOUT_SECONDS] is deliberately
 * far shorter than [Http]'s stream-oriented timeouts: a lyric that arrives
 * after the second chorus is of no use to anyone, and the fallbacks behind it
 * are the better answer.
 */
private const val LYRICS_TIMEOUT_SECONDS = 6L

internal const val LYRICS_AGENT = "BitChord (https://github.com/bitchord)"

val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val client by lazy {
    // Derived from the shared client, so the connection pool and DNS stay
    // common — only the deadline differs.
    Http.client.newBuilder()
        .callTimeout(LYRICS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
}

// PaxSenix's authenticated routes often have to query an upstream catalogue
// before answering. Keep the fast deadline for the ordinary providers, but
// match PaxSenix's own client timeout here so valid requests are not discarded
// while its backend is still resolving a track.
private val authenticatedClient by lazy {
    client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
}

/**
 * A response body, or null for a miss.
 *
 * A refusal or a timeout is not the provider saying it has no lyrics: it is
 * the provider not being asked successfully. Surfacing those three statuses
 * lets [LyricsFailure] report blocked/timeout in the provider sheet instead
 * of drawing every failure as "not found". Any other status keeps the old
 * null-on-miss behavior, since several providers use their own fallback URL
 * after a 404.
 */
private fun Response.bodyOrStatus(): String? {
    if (isSuccessful) return body?.string()
    if (code == 403 || code == 429 || code == 408) throw LyricsHttpStatusException(code)
    return null
}

/** The body, with a refusal/timeout surfaced and every other failure a null. */
private inline fun <T> statusAware(block: () -> T): T? = try {
    block()
} catch (status: LyricsHttpStatusException) {
    throw status
} catch (transport: java.io.IOException) {
    // A dead network or a request past its budget is not "no lyrics": the
    // provider sheet must be able to say TIMEOUT/NETWORK rather than NOT_FOUND.
    throw transport
} catch (_: Throwable) {
    null
}

/** Body of a successful GET, or null for any failure at all. */
internal fun lyricsGet(url: String): String? = statusAware {
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .build()
    client.newCall(request).execute().use { response -> response.bodyOrStatus() }
}

/** Body of an authenticated provider GET without service-specific browser headers. */
internal fun lyricsGetBearer(url: String, bearer: String): String? = statusAware {
    if (bearer.isBlank()) return null
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json, text/plain, */*")
        .header("Authorization", "Bearer $bearer")
        .build()
    authenticatedClient.newCall(request).execute().use { response -> response.bodyOrStatus() }
}

/**
 * [lyricsGet], with a bearer token and the headers Apple's own web player
 * sends alongside one — `amp-api.music.apple.com` answers a token with no
 * `Origin` at all the same way it answers a wrong one, with a 403.
 */
internal fun lyricsGetAuthorized(url: String, bearer: String): String? = statusAware {
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $bearer")
        .header("Origin", "https://music.apple.com")
        .header("Referer", "https://music.apple.com/")
        .build()
    client.newCall(request).execute().use { response -> response.bodyOrStatus() }
}

/**
 * [lyricsGet] with the headers a service's own web page sends — the Korean
 * services answer their web player's requests, so the request looks like one
 * of those: a browser agent, the page as referer, nothing signed.
 */
internal fun lyricsGetAsBrowser(url: String, referer: String, accept: String = "application/json"): String? = statusAware {
    val request = Request.Builder().url(url)
        .header("User-Agent", BROWSER_AGENT)
        .header("Accept", accept)
        .header("Accept-Language", "ko-KR,ko;q=0.9")
        .header("Referer", referer)
        .build()
    client.newCall(request).execute().use { response -> response.bodyOrStatus() }
}

private const val BROWSER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"
