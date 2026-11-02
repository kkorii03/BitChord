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
        if (uri.scheme == SCHEME && uri.authority == "track") {
            uri.lastPathSegment?.takeIf { TelegramMessageMapper.parseId(it) != null }
        } else {
            null
        }

    /** [trackId] for the string form, without an Android Uri (downloads run on a plain JVM too). */
    fun trackId(url: String): String? {
        val prefix = "$SCHEME://track/"
        if (!url.startsWith(prefix)) return null
        return url.substring(prefix.length)
            .substringBefore('?')
            .substringBefore('#')
            .takeIf { TelegramMessageMapper.parseId(it) != null }
    }
}
