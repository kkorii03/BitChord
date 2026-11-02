package com.music.bitchord.telegram

import android.net.Uri

/**
 * The internal URI a Telegram stream travels under between the source resolver
 * and the data source.
 *
 * It never leaves the process: no HTTP client sees it, and the player's
 * DataSource routing turns it into TDLib reads before any network code runs.
 */
object TelegramStreamUri {

    const val SCHEME = "bitchord-telegram"

    fun of(trackId: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("track")
            .appendPath(trackId)
            .build()
            .toString()

    fun trackId(uri: Uri): String? =
        trackIdOf(uri.scheme, uri.authority, uri.lastPathSegment)

    /** The same internal URI as a string, parsable on a JVM without Android's Uri. */
    fun trackId(url: String): String? {
        if (!url.startsWith("$SCHEME:")) return null
        return runCatching { java.net.URI(url) }.getOrNull()?.let { parsed ->
            trackIdOf(parsed.scheme, parsed.authority, parsed.path?.substringAfterLast('/'))
        }
    }

    private fun trackIdOf(scheme: String?, authority: String?, lastPathSegment: String?): String? =
        if (scheme == SCHEME && authority == "track") {
            lastPathSegment?.takeIf { TelegramMessageMapper.parseId(it) != null }
        } else {
            null
        }
}
