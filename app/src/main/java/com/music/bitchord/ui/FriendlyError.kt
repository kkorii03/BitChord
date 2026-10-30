package com.music.bitchord.ui

import android.content.Context
import com.music.bitchord.R
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

    fun message(context: Context, error: Throwable): String {
        val raw = error.message.orEmpty()
        if (raw.any { it in '\uAC00'..'\uD7A3' }) return raw
        val lower = raw.lowercase(Locale.ROOT)
        val status = HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            error is UnknownHostException ||
                lower.contains("resolve host") ||
                lower.contains("unable to resolve") ||
                lower.contains("unknownhost") -> context.getString(R.string.no_internet_connection)
            error is SocketTimeoutException ||
                error is TimeoutException ||
                lower.contains("timed out") ||
                lower.contains("timeout") -> context.getString(R.string.connection_timed_out)
            error is SSLException ||
                lower.contains("ssl") ||
                lower.contains("certificate") -> context.getString(R.string.secure_connection_failed)
            lower.contains("proxy") -> context.getString(R.string.proxy_unreachable)
            status == 429 || status in 500..599 || lower.contains("too many requests") ->
                context.getString(R.string.server_busy)
            status == 401 || status == 403 ||
                lower.contains("unauthorized") ||
                lower.contains("forbidden") -> context.getString(R.string.youtube_request_rejected)
            error is ConnectException ||
                lower.contains("failed to connect") ||
                lower.contains("connection refused") ||
                lower.contains("connect failed") -> context.getString(R.string.connection_failed)
            else -> context.getString(R.string.something_went_wrong)
        }
    }

    private val HTTP_STATUS = Regex("""\b(4\d{2}|5\d{2})\b""")
}
