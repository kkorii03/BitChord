package com.music.bitchord.ui

import com.music.bitchord.data.lyrics.LyricsFailure

/** What the current track's provider picker already knows without another request. */
enum class LyricsProviderState {
    NOT_FETCHED,
    FETCHING,
    FOUND,
    NOT_FOUND,

    /** The provider ran out of time before it answered. */
    TIMEOUT,

    /** Could not reach the provider at all. */
    NETWORK_ERROR,

    /** The provider refused the request (403/429), rather than not knowing the track. */
    BLOCKED,
    ;

    /** Whether this state means "a request finished without lyrics". */
    val isMiss: Boolean
        get() = this == NOT_FOUND || this == TIMEOUT || this == NETWORK_ERROR || this == BLOCKED

    companion object {
        /** The row state for a finished attempt that produced no lyrics. */
        fun fromFailure(failure: LyricsFailure?): LyricsProviderState = when (failure) {
            null, LyricsFailure.NOT_FOUND -> NOT_FOUND
            LyricsFailure.TIMEOUT -> TIMEOUT
            LyricsFailure.NETWORK -> NETWORK_ERROR
            LyricsFailure.BLOCKED -> BLOCKED
        }
    }
}
