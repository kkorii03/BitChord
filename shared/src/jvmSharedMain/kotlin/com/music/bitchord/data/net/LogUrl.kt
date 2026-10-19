package com.music.bitchord.data.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A URL as it is safe to write to a log: scheme, host and path, never the
 * query or fragment. Stream and extractor URLs carry signed parameters
 * (`expire`, `sig`, visitor-bound values), and a diagnostic export must not
 * take those off the phone.
 *
 * Pure so the rule is tested without a device or a network.
 */
object LogUrl {

    /** [url] without its query or fragment, or `[invalid-url]` when unparseable. */
    fun of(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "[invalid-url]"
        return "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
    }
}
