package com.music.bitchord.data.lyrics

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a provider attempt did not answer with lyrics.
 *
 * Before this existed every failure — a dead network, a request that ran past
 * its budget, a host that refuses this client — reached the provider sheet as
 * `null`, which it drew as "not found". The listener could not tell a track no
 * database has from a phone that was offline, and retrying looked pointless in
 * both cases. The reason travels alongside the miss so the sheet can say which
 * one it was, while remaining a plain miss to the lookup itself.
 */
enum class LyricsFailure {
    /** The provider answered and had nothing for this track. */
    NOT_FOUND,

    /** The lookup's own budget ran out before the provider answered. */
    TIMEOUT,

    /** No usable connection: DNS, connect or read failure. */
    NETWORK,

    /** The host refused the request (403/429) rather than not knowing the track. */
    BLOCKED,
    ;

    companion object {
        /** Classifies an exception thrown by a provider attempt. */
        fun from(error: Throwable?): LyricsFailure = when (error) {
            null -> NOT_FOUND
            is LyricsHttpStatusException -> fromStatus(error.code)
            is SocketTimeoutException, is InterruptedIOException -> TIMEOUT
            is UnknownHostException, is ConnectException -> NETWORK
            // OkHttp reports most transport trouble as a plain IOException; a
            // provider that reached this point did not produce an answer.
            is java.io.IOException -> NETWORK
            else -> NETWORK
        }

        /** Classifies an HTTP status the provider surfaced. */
        fun fromStatus(code: Int): LyricsFailure = when {
            code == 403 || code == 429 -> BLOCKED
            code == 408 -> TIMEOUT
            code in 400..499 -> NOT_FOUND
            else -> NETWORK
        }
    }
}

/** An HTTP status a provider chose to surface instead of swallowing. */
class LyricsHttpStatusException(val code: Int) : Exception("HTTP $code")
