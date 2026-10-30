package com.music.bitchord.data

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.net.CounterpartSwitch
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.YouTubeProxy
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Removes the things a diagnostic log must never carry off the phone: cookies,
 * session identifiers, authorization headers, API keys, email addresses and
 * the query strings of every http(s) URL (the host and path are kept). Signed
 * media URLs are not unique to googlevideo: JioSaavn and module sources log
 * theirs too, and a query is where their signature and expiry live.
 *
 * Pure and side-effect free so the rules can be tested without a device.
 */
object LogRedactor {
    private val COOKIE = Regex("""(?i)((?:set-)?cookie\s*[:=]\s*)[^\r\n]+""")
    private val AUTHORIZATION = Regex("""(?i)(authorization\s*[:=]\s*)(?:bearer\s+)?[^\s,;]+""")
    private val SESSION_ID = Regex(
        """(?i)\b(SAPISID|APISID|HSID|SSID|SID|__Secure-[A-Za-z0-9_]+)\s*[=:]\s*[^\s;,]+""",
    )
    private val GOOGLE_API_KEY = Regex("""AIza[0-9A-Za-z_\-]{20,}""")
    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val URL_USERINFO = Regex("""(?i)\b(https?://)[^/\s@]+@""")
    private val URL_QUERY = Regex("""(?i)(https?://[^\s?#]+)[?#][^\s]*""")

    fun redact(input: String): String {
        var out = COOKIE.replace(input, "\$1[redacted]")
        out = AUTHORIZATION.replace(out, "\$1[redacted]")
        out = SESSION_ID.replace(out, "\$1=[redacted]")
        out = GOOGLE_API_KEY.replace(out, "[redacted-key]")
        out = SK_KEY.replace(out, "[redacted-key]")
        out = URL_QUERY.replace(out, "\$1")
        out = URL_USERINFO.replace(out, "\$1[redacted]@")
        out = EMAIL.replace(out, "[redacted-email]")
        return out
    }
}

/** A written diagnostic log: its file name, and a URI another app can read. */
data class SavedLog(val fileName: String, val uri: Uri, val inDownloads: Boolean)

/** Writes the last few thousand log lines plus the app's state to a text file. */
object DiagnosticsExport {
    private const val FOLDER = "diagnostics"
    private const val MIME = "text/plain"

    private val SESSION_FLAGS = listOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO",
    )

    private val WEBVIEW_ORIGINS = listOf(
        "https://www.youtube.com",
        "https://music.youtube.com",
        "https://www.google.com",
    )

    suspend fun write(context: Context, signedIn: Boolean): SavedLog? {
        val webViewNames = withContext(Dispatchers.Main) { webViewCookieNames() }
        return withContext(Dispatchers.IO) {
            runCatching {
                val now = Date()
                val fileName =
                    "BitChord-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}.txt"
                val locale = context.resources.configuration.locales[0]
                val proxy = AppSettings.proxyMode.value
                val cookie = runCatching {
                    val store = AuthStore(context)
                    store.activeSession?.cookie ?: store.cookie
                }.getOrNull()
                val authCookieNames = cookieNames(cookie)
                val logText = LogRedactor.redact(TrackLog.exportText())
                val oldestLine = oldestLineAt(logText)
                val translationSetting = if (AppSettings.autoTranslateLyrics.value) {
                    "auto" + AppSettings.translationLanguage.value
                        .takeIf { it.isNotBlank() }?.let { "->$it" }.orEmpty()
                } else {
                    "off"
                }
                val header = buildString {
                    appendLine("BitChord diagnostic log")
                    appendLine("exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}")
                    appendLine(
                        "version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                            "${BuildConfig.BUILD_TYPE}",
                    )
                    appendLine("git: ${BuildConfig.GIT_SHA.ifBlank { "unknown" }}")
                    appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("signed in: ${if (signedIn) "yes" else "no"}")
                    appendLine("locale: ${locale.toLanguageTag()} region: ${locale.country.ifEmpty { "?" }}")
                    appendLine("music gate active: ${MusicGateFallback.active.value}")
                    MusicGateFallback.describe().forEach { appendLine("music gate: $it") }
                    appendLine("proxy: ${if (proxy == ProxyMode.OFF) "off" else "on (${proxy.name.lowercase(Locale.US)})"}")
                    appendLine("proxy scope: ${AppSettings.proxyScope.value.name.lowercase(Locale.US)}")
                    appendLine(
                        "proxy route: ${YouTubeProxy.activeRoute.value.name.lowercase(Locale.US)}" +
                            " backup=${if (YouTubeProxy.config.backupUsable) "yes" else "no"}",
                    )
                    val privateSince = AppSettings.privateListeningSince()
                    appendLine(
                        "private listening: ${if (AppSettings.privateListening.value) "on" else "off"}" +
                            if (privateSince > 0L) {
                                " since ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(privateSince))}"
                            } else {
                                ""
                            },
                    )
                    appendLine("music only: ${if (AppSettings.preferMusicOnly.value) "on" else "off"}")
                    appendLine(
                        "playback settings: " +
                            "autoplay=${onOff(AppSettings.autoplay.value)} " +
                            "personalisedAutoplay=${onOff(AppSettings.personalisedAutoplay.value)} " +
                            "crossfade=${AppSettings.crossfadeSeconds.value}s " +
                            "smartFade=${onOff(AppSettings.smartFadeEnabled.value)} " +
                            "quality=${AppSettings.audioQualityWifi.value.name.lowercase(Locale.US)}/" +
                            "${AppSettings.audioQualityCellular.value.name.lowercase(Locale.US)} " +
                            "lowPower=${AppSettings.lowPowerMode.value.name.lowercase(Locale.US)} " +
                            "canvas=${onOff(AppSettings.animatedCanvas.value)}" +
                            "/video=${onOff(AppSettings.videoCanvas.value)} " +
                            "skipSilence=${onOff(AppSettings.skipSilence.value)} " +
                            "skipNonMusic=${onOff(AppSettings.skipNonMusicParts.value)} " +
                            "skipSponsor=${onOff(AppSettings.skipSponsorParts.value)}",
                    )
                    appendLine(
                        "lyrics settings: synced=${onOff(AppSettings.syncedLyrics.value)} " +
                            "providers=${lyricsProviders()} " +
                            "aiTiming=${onOff(AppSettings.autoLyricsTiming.value)} " +
                            "aiTimingAhead=${onOff(AppSettings.aiTimingAhead.value)} " +
                            "translation=$translationSetting",
                    )
                    appendLine("proxy memory: ${ProxyPlayback.rememberedCount()} tracks")
                    appendLine("song pairing: ${counterpartState(now)}")
                    appendLine("log retention: ~5 MB across 3 files, oldest $oldestLine")
                    DiagnosticsState.describe().forEach { appendLine(it) }
                    appendLine("power: ${powerState(context)}")
                    appendLine("network: ${networkType(context)}")
                    appendLine("auth cookie length: ${cookie?.length ?: 0}")
                    appendLine("auth cookie names: ${authCookieNames.joinToString(", ").ifEmpty { "(none)" }}")
                    appendLine(
                        "auth cookie flags: " +
                            SESSION_FLAGS.joinToString(" ") { flag ->
                                "$flag=${if (flag in authCookieNames) "y" else "n"}"
                            },
                    )
                    appendLine("auth cookie captured from: not recorded")
                    webViewNames.forEach { appendLine(it) }
                    appendLine("---")
                }
                val content = header + logText + "\n"
                val downloadsUri = saveToDownloads(context, fileName, content)
                val uri = downloadsUri ?: FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(context.cacheDir, FOLDER).apply { mkdirs() }
                        .let { dir -> File(dir, fileName).apply { writeText(content) } },
                )
                SavedLog(fileName, uri, inDownloads = downloadsUri != null)
            }.getOrNull()
        }
    }

    /** The user-visible copy, in the public Downloads folder where a file app can find it. */
    private fun saveToDownloads(context: Context, fileName: String, content: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return runCatching {
            val stream = resolver.openOutputStream(uri)
                ?: error("no output stream for $uri")
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    /** Enabled lyric providers in the order they are asked, labels only. */
    private fun lyricsProviders(): String {
        val enabled = AppSettings.lyricsSources.value
        val order = (AppSettings.lyricsSourceOrder.value + LyricsSource.offered).distinct()
        return order.filter { it in enabled && !it.hidden }
            .joinToString(">") { it.label }
            .ifEmpty { "(none)" }
    }

    private fun counterpartState(now: Date): String {
        val until = CounterpartSwitch.offUntil() ?: return "on"
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(until))
        return if (CounterpartSwitch.isSuppressed(now.time)) "off until $at" else "on (stale deadline $at)"
    }

    /**
     * The wall-clock time of the oldest line in the exported text, so a reader
     * knows how far back the file actually reaches. Persisted lines carry a
     * date; an in-memory ring only has a clock, which is still better than
     * saying nothing.
     */
    private fun oldestLineAt(text: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return "(none)"
        return runCatching {
            if (first.length >= 23 && first[4] == '-' && first[10] == ' ') {
                val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .parse(first.substring(0, 23))
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(at!!)
            } else {
                first.substringBefore(' ')
            }
        }.getOrElse { "(unknown)" }
    }

    private fun powerState(context: Context): String {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val saver = manager?.isPowerSaveMode == true
        return "saver=${if (saver) "on" else "off"} " +
            "lowPower=${if (AppSettings.lowPower.value) "on" else "off"} " +
            "mode=${AppSettings.lowPowerMode.value.name.lowercase(Locale.US)} " +
            "battery=${AppSettings.batteryPercentValue()}%"
    }

    private fun networkType(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "unknown"
        val network = manager.activeNetwork ?: return@runCatching "none"
        val caps = manager.getNetworkCapabilities(network) ?: return@runCatching "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }.getOrElse { "unknown" }

    private fun cookieNames(header: String?): List<String> = header.orEmpty()
        .split(';')
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

    /** Names only, never values: the log must not carry a credential off the phone. */
    private fun webViewCookieNames(): List<String> = runCatching {
        val manager = CookieManager.getInstance()
        WEBVIEW_ORIGINS.map { origin ->
            val names = manager.getCookie(origin)
                ?.split(';')
                ?.map { it.substringBefore('=').trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            "webview cookie names ${origin.substringAfter("://")}: " +
                names.joinToString(", ").ifEmpty { "(none)" }
        }
    }.getOrElse { listOf("webview cookie names: unreadable (${it.message})") }
}
