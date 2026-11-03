package com.music.bitchord.data.innertube

/**
 * How many times a music video's picture is asked for before the player settles
 * on the still artwork. YouTube can have no video-only stream for a track that
 * plays audio fine; without a cap the resolver walks every client again on each
 * surface or track change for the length of the song.
 */
object VideoPictureRetry {

    /** Three failed asks per video; a sign-in starts a fresh budget. */
    const val MAX_ATTEMPTS = 3

    /** Whether one more ask is allowed after [attempts] failed attempts. */
    fun canTry(attempts: Int): Boolean = attempts < MAX_ATTEMPTS
}
