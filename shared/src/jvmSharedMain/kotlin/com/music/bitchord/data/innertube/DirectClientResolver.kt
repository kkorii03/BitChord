package com.music.bitchord.data.innertube

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A plain, anonymous `/player` request as the YouTube iOS or Android app makes
 * it, for when every richer path is refused.
 *
 * Measured from a Korean IP on an account without Premium (2026-10-18): the
 * YouTube Music web player, MWEB and VISIONOS all answer "Music Premium
 * 회원만 시청할 수 있는 콘텐츠입니다", ANDROID_VR asks for a sign-in to prove it
 * is not a bot, and the embedded/TV clients error — while IOS and ANDROID,
 * asked anonymously, return direct Opus URLs (itag 251, ~135-152 kbps) that
 * googlevideo serves with 206. Nothing here is signed in: playing a track
 * needs no account, and sending the account's cookies with a client the
 * account is not entitled on is what draws the Premium refusal.
 *
 * The client versions and user agents are [PlayerClient]'s own, so the media
 * fetch that follows (headers from [PlayerClient.forStreamUrl], keyed on the
 * URL's `c` / `cver`) matches the client that minted the URL.
 */
internal object DirectClientResolver {

    class Found(val url: String, val kbps: Int, val mimeType: String, val client: String)

    private class Spec(
        val name: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private val SPECS = listOf(
        Spec(
            name = "IOS",
            version = "21.26.4",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82",
            ),
        ),
        Spec(
            name = "ANDROID",
            version = "21.26.364",
            userAgent = "com.google.android.youtube/21.26.364 " +
                "(Linux; U; Android 15; en_US; Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
            extra = mapOf(
                "androidSdkVersion" to 35,
                "osName" to "Android",
                "osVersion" to "15",
            ),
        ),
    )

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Its own short-leashed client built from the app's, with no cookie jar,
     * so nothing from a signed-in session can ride along.
     */
    private val client by lazy {
        Http.client.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Candidate audio URLs for [videoId], best first under [maxKbps], one
     * client after another. Each candidate still has to pass the caller's
     * probe; this only asks.
     */
    fun candidates(videoId: String, maxKbps: Int): Sequence<Found> = sequence {
        for (spec in SPECS) {
            val found = runCatching { ask(spec, videoId, maxKbps) }
                .onFailure { TrackLog.w(TAG, "direct ${spec.name} failed for $videoId: ${it.message}") }
                .getOrNull()
            if (found != null) yield(found)
        }
    }

    private fun ask(spec: Spec, videoId: String, maxKbps: Int): Found? {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", spec.name)
                    put("clientVersion", spec.version)
                    put("hl", "ko")
                    put("gl", "KR")
                    for ((key, value) in spec.extra) {
                        when (value) {
                            is Int -> put(key, value)
                            else -> put(key, value.toString())
                        }
                    }
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val request = okhttp3.Request.Builder()
            .url(PLAYER_URL)
            .header("User-Agent", spec.userAgent)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                TrackLog.w(TAG, "direct ${spec.name} HTTP ${response.code} for $videoId")
                return null
            }
            response.body?.string() ?: return null
        }
        val root = json.parseToJsonElement(text).jsonObject
        return pickAudio(root, maxKbps, spec.name, videoId)
    }

    /** The best direct (unciphered) audio format under [maxKbps]; pure, so it can be tested. */
    internal fun pickAudio(root: JsonObject, maxKbps: Int, client: String, videoId: String = ""): Found? {
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        if (status != "OK") {
            val reason = root["playabilityStatus"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            TrackLog.w(TAG, "direct $client $status for $videoId: ${reason.orEmpty().take(80)}")
            return null
        }
        val formats = root["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray ?: return null
        val audio = formats.mapNotNull { element ->
            val f = element.jsonObject
            val mime = f["mimeType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            val url = (f["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val bitrate = f["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
            Found(url, bitrate / 1000, mime.substringBefore(';').trim(), client)
        }
        if (audio.isEmpty()) return null
        val ceiling = maxKbps.coerceAtLeast(1)
        return audio.filter { it.kbps <= ceiling }.maxByOrNull { it.kbps }
            ?: audio.minByOrNull { it.kbps }
    }

    private const val TAG = "DirectClient"
}
