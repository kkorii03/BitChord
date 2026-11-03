package com.music.bitchord.data.innertube

/**
 * How many times a video's picture is asked before it is remembered as
 * missing. A network failure is worth another surface or track change; a
 * definite "no playable clients" is not, and asking it on every repaint is the
 * once-a-second loop the phone log showed.
 */
object VideoPictureRetry {
    const val MAX_ATTEMPTS = 3

    /** Whether this failure should stop every later ask for the same video. */
    fun giveUp(attempts: Int, transient: Boolean): Boolean = !transient || attempts >= MAX_ATTEMPTS
}
