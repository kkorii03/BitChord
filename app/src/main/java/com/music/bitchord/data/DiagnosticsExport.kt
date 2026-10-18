package com.music.bitchord.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.net.ProxyMode
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
 * the query strings of googlevideo URLs (the host and path are kept).
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
    private val GOOGLEVIDEO_URL = Regex("""(?i)(https?://[^\s?#]*googlevideo[^\s?#]*)(\?[^\s]*)?""")

    fun redact(input: String): String {
        var out = COOKIE.replace(input, "\$1[redacted]")
        out = AUTHORIZATION.replace(out, "\$1[redacted]")
        out = SESSION_ID.replace(out, "\$1=[redacted]")
        out = GOOGLE_API_KEY.replace(out, "[redacted-key]")
        out = SK_KEY.replace(out, "[redacted-key]")
        out = EMAIL.replace(out, "[redacted-email]")
        out = GOOGLEVIDEO_URL.replace(out, "\$1")
        return out
    }
}

/** Writes the last few thousand log lines plus the app's state to a shareable text file. */
object DiagnosticsExport {
    private const val FOLDER = "diagnostics"

    suspend fun write(context: Context, signedIn: Boolean): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val now = Date()
            val dir = File(context.cacheDir, FOLDER).apply { mkdirs() }
            val file = File(
                dir,
                "BitChord-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}.txt",
            )
            val locale = context.resources.configuration.locales[0]
            val proxy = AppSettings.proxyMode.value
            val header = buildString {
                appendLine("BitChord diagnostic log")
                appendLine("exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}")
                appendLine(
                    "version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                        "${BuildConfig.BUILD_TYPE}",
                )
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("signed in: ${if (signedIn) "yes" else "no"}")
                appendLine("locale: ${locale.toLanguageTag()} region: ${locale.country.ifEmpty { "?" }}")
                appendLine("music gate active: ${MusicGateFallback.active.value}")
                MusicGateFallback.describe().forEach { appendLine("music gate: $it") }
                appendLine("proxy: ${if (proxy == ProxyMode.OFF) "off" else "on (${proxy.name.lowercase(Locale.US)})"}")
                appendLine("---")
            }
            file.writeText(header + LogRedactor.redact(TrackLog.everything().joinToString("\n")) + "\n")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
}
