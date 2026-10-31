package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.sources.addon.SourceFormats
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * One Korean sentence for a failure, never the exception's own English text.
 *
 * Apple Music tells the listener what happened and what to do next; a stack
 * trace class name in the middle of a Korean screen reads as a broken app.
 * Messages our own code wrote for the listener are already Korean and are left
 * alone — only the technical leftovers are replaced.
 */
object FriendlyError {
    /**
     * The same sentence for a failure that only survives as text.
     *
     * [SourceHealth.Unreachable] carries a reason string rather than the
     * exception that produced it, and a source screen must not print the
     * server's English `Failed to connect to …` in the middle of a Korean
     * row. Wrapping it keeps one classification table for both shapes.
     */
    fun message(context: Context, rawReason: String): String =
        message(context, IllegalStateException(rawReason))

    /**
     * A source status line, localised for the two addon-address refusals that
     * have a string of their own. Every other reason is already written for the
     * listener and is returned unchanged.
     */
    fun reason(context: Context, rawReason: String): String = when (rawReason) {
        SourceFormats.NOT_ADDON_URL -> context.getString(R.string.source_addon_not_addon)
        SourceFormats.REPOSITORY_URL -> context.getString(R.string.source_addon_repository_url)
        else -> rawReason
    }

    fun message(context: Context, error: Throwable): String {
        val raw = error.message.orEmpty()
        when (raw) {
            SourceFormats.NOT_ADDON_URL -> return context.getString(R.string.source_addon_not_addon)
            SourceFormats.REPOSITORY_URL -> return context.getString(R.string.source_addon_repository_url)
        }
        if (raw.any { it in '\uAC00'..'\uD7A3' }) return raw
        return context.getString(resourceFor(error))
    }

    /**
     * The catalogue row behind [message]. Kept free of [Context] so the mapping
     * can be tested on the JVM and so every branch has one named cause instead
     * of one shared "something went wrong".
     */
    internal fun resourceFor(error: Throwable): Int {
        val raw = error.message.orEmpty()
        if (raw.any { it in '\uAC00'..'\uD7A3' }) return R.string.something_went_wrong
        val lower = raw.lowercase(Locale.ROOT)
        val status = HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            error is UnknownHostException ||
                lower.contains("resolve host") ||
                lower.contains("unable to resolve") ||
                lower.contains("unknownhost") -> R.string.no_internet_connection
            error is SocketTimeoutException ||
                error is TimeoutException ||
                lower.contains("timed out") ||
                lower.contains("timeout") -> R.string.connection_timed_out
            error is SSLException ||
                lower.contains("ssl") ||
                lower.contains("certificate") -> R.string.secure_connection_failed
            lower.contains("proxy") -> R.string.proxy_unreachable
            status == 429 || status in 500..599 || lower.contains("too many requests") ->
                R.string.server_busy
            status == 401 || status == 403 ||
                lower.contains("unauthorized") ||
                lower.contains("forbidden") -> R.string.youtube_request_rejected
            status == 404 || lower.contains("not found") -> R.string.error_not_found
            status == 400 || status == 422 ||
                lower.contains("bad request") -> R.string.error_request_invalid
            status == 410 ||
                lower.contains("not available") ||
                lower.contains("unavailable") ||
                lower.contains("no suitable") ||
                lower.contains("no playable") -> R.string.error_content_unavailable
            error is ConnectException ||
                lower.contains("failed to connect") ||
                lower.contains("connection refused") ||
                lower.contains("connect failed") -> R.string.connection_failed
            else -> R.string.something_went_wrong
        }
    }

    private val HTTP_STATUS = Regex("""\b(4\d{2}|5\d{2})\b""")
}
